package com.yukina.codingagent.agent.plan;

import com.yukina.codingagent.agent.AgentRunResult;
import com.yukina.codingagent.deepseek.DeepSeekToolCall;
import com.yukina.codingagent.deepseek.DeepSeekToolDefinition;
import com.yukina.codingagent.tool.ToolExecutionResult;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 定义并执行运行内 update_plan 协议，对 AI 提议的状态转换和工具证据进行审批。
 */
@Component
public class PlanCoordinator {

    public static final String TOOL_NAME = "update_plan";
    private static final Set<String> BLOCK_REASON_CODES = Set.of(
            "ENVIRONMENT_MISSING", "MISSING_INPUT", "PERMISSION_DENIED", "SAFETY_RESTRICTION"
    );
    private static final Map<String, Set<String>> BLOCK_EVIDENCE_CODES = Map.of(
            "ENVIRONMENT_MISSING", Set.of("COMMAND_NOT_FOUND"),
            "MISSING_INPUT", Set.of("PATH_NOT_FOUND", "NOT_A_FILE"),
            "PERMISSION_DENIED", Set.of(
                    "PATH_ACCESS_FAILED", "FILE_READ_FAILED", "FILE_WRITE_FAILED", "FILE_EDIT_FAILED",
                    "FILE_DELETE_FAILED", "DIRECTORY_LIST_FAILED"
            ),
            "SAFETY_RESTRICTION", Set.of(
                    "COMMAND_NOT_ALLOWED", "ABSOLUTE_PATH_FORBIDDEN", "PATH_OUTSIDE_WORKSPACE",
                    "SYMLINK_WRITE_FORBIDDEN", "SYMLINK_DELETE_FORBIDDEN"
            )
    );

    private final ObjectMapper objectMapper;
    private final DeepSeekToolDefinition definition;

    /** @param objectMapper update_plan 参数和 Observation JSON 转换器 */
    public PlanCoordinator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.definition = createDefinition();
    }

    /** @return 可与工作区工具一同发送给模型的 update_plan 定义 */
    public DeepSeekToolDefinition definition() {
        return definition;
    }

    /**
     * Reflection 要求修正时重新打开最后一个已完成步骤，并清除其旧证据。
     * 该操作不增删步骤，不属于动态重规划。
     *
     * @param current Reflection 前全部完成的计划
     * @return 最后一个完成步骤恢复为 IN_PROGRESS 的新计划
     */
    public AgentPlan reopenLastStepForRevision(AgentPlan current, int evidenceFromToolStep) {
        List<PlanStep> steps = new ArrayList<>(current.steps());
        for (int index = steps.size() - 1; index >= 0; index--) {
            PlanStep step = steps.get(index);
            if (step.status() == PlanStepStatus.COMPLETED) {
                steps.set(index, step.withState(
                        PlanStepStatus.IN_PROGRESS,
                        List.of(),
                        null,
                        evidenceFromToolStep
                ));
                return new AgentPlan(current.goal(), steps, current.acceptanceCriteria());
            }
        }
        return current;
    }

    /**
     * 校验并应用模型提出的完整计划状态快照。
     *
     * @param toolCall update_plan 工具调用
     * @param current 当前不可变计划
     * @param toolSteps 已产生的真实工具证据
     * @return 接受后的新计划或包含拒绝原因的原计划
     */
    public PlanUpdateResult update(
            DeepSeekToolCall toolCall,
            AgentPlan current,
            List<AgentRunResult.ToolStep> toolSteps
    ) {
        if (toolCall == null || toolCall.function() == null || toolCall.id() == null || toolCall.id().isBlank()) {
            return failure(toolCall == null ? null : toolCall.id(), current, "INVALID_TOOL_CALL",
                    "update_plan requires a valid tool call id");
        }
        if (!TOOL_NAME.equals(toolCall.function().name())) {
            return failure(toolCall.id(), current, "INVALID_TOOL_CALL", "Expected update_plan tool call");
        }
        try {
            JsonNode arguments = objectMapper.readTree(toolCall.function().arguments());
            if (arguments == null || !arguments.isObject()) {
                return failure(toolCall.id(), current, "INVALID_ARGUMENTS",
                        "update_plan arguments must be a JSON object");
            }
            String summary = arguments.path("summary").asText().trim();
            if (summary.isBlank()) {
                return failure(toolCall.id(), current, "INVALID_ARGUMENTS", "summary must not be blank");
            }
            JsonNode updates = arguments.path("steps");
            if (!updates.isArray() || updates.size() != current.steps().size()) {
                return failure(toolCall.id(), current, "INVALID_ARGUMENTS",
                        "steps must contain every existing plan step exactly once");
            }

            Map<String, IndexedEvidence> evidence = indexEvidence(toolSteps);
            Map<String, RequestedStep> requestedById = new LinkedHashMap<>();
            for (JsonNode node : updates) {
                RequestedStep requested = parseRequestedStep(node);
                if (requestedById.putIfAbsent(requested.id(), requested) != null) {
                    return failure(toolCall.id(), current, "INVALID_ARGUMENTS", "plan step ids must be unique");
                }
            }
            if (!requestedById.keySet().equals(current.steps().stream().map(PlanStep::id).collect(
                    java.util.stream.Collectors.toSet()))) {
                return failure(toolCall.id(), current, "INVALID_ARGUMENTS",
                        "plan step ids cannot be added, removed, or replaced");
            }

            List<PlanStep> nextSteps = new ArrayList<>();
            Set<String> usedEvidenceIds = new HashSet<>();
            int inProgress = 0;
            boolean changed = false;
            for (PlanStep step : current.steps()) {
                RequestedStep requested = requestedById.get(step.id());
                String transitionError = validateTransition(step.status(), requested.status());
                if (transitionError != null) {
                    return failure(toolCall.id(), current, "PLAN_UPDATE_REJECTED",
                            step.id() + ": " + transitionError);
                }
                if (requested.status() == PlanStepStatus.IN_PROGRESS) {
                    inProgress++;
                }
                PlanBlocker blocker = null;
                List<String> nextEvidence = List.of();
                if (step.status() == PlanStepStatus.COMPLETED
                        && requested.status() == PlanStepStatus.COMPLETED) {
                    nextEvidence = step.evidenceToolCallIds();
                    usedEvidenceIds.addAll(nextEvidence);
                } else if (step.status() == PlanStepStatus.BLOCKED
                        && requested.status() == PlanStepStatus.BLOCKED) {
                    nextEvidence = step.evidenceToolCallIds();
                    blocker = step.blocker();
                    usedEvidenceIds.addAll(nextEvidence);
                } else if (requested.status() == PlanStepStatus.COMPLETED) {
                    EvidenceSelection selection = selectCompletionEvidence(
                            requested.evidenceIds(),
                            evidence,
                            step.evidenceFromToolStep(),
                            step.evidenceType(),
                            usedEvidenceIds
                    );
                    if (selection.error() != null) {
                        return failure(toolCall.id(), current, "PLAN_UPDATE_REJECTED",
                                step.id() + ": " + selection.error());
                    }
                    nextEvidence = selection.evidenceIds();
                } else if (requested.status() == PlanStepStatus.BLOCKED) {
                    BlockValidation block = validateBlocker(
                            requested,
                            evidence,
                            step.evidenceFromToolStep(),
                            usedEvidenceIds
                    );
                    if (block.error() != null) {
                        return failure(toolCall.id(), current, "PLAN_UPDATE_REJECTED",
                                step.id() + ": " + block.error());
                    }
                    blocker = block.blocker();
                    nextEvidence = blocker.evidenceToolCallIds();
                }
                int nextEvidenceFrom = step.evidenceFromToolStep();
                if (requested.status() == PlanStepStatus.PENDING) {
                    nextEvidenceFrom = -1;
                } else if (step.status() == PlanStepStatus.PENDING) {
                    nextEvidenceFrom = requested.status() == PlanStepStatus.IN_PROGRESS
                            ? (toolSteps == null ? 0 : toolSteps.size())
                            : 0;
                } else if (requested.status() == PlanStepStatus.IN_PROGRESS
                        && step.status() != PlanStepStatus.IN_PROGRESS) {
                    nextEvidenceFrom = toolSteps == null ? 0 : toolSteps.size();
                }
                nextSteps.add(step.withState(
                        requested.status(),
                        nextEvidence,
                        blocker,
                        nextEvidenceFrom
                ));
                changed |= step.status() != requested.status()
                        || !step.evidenceToolCallIds().equals(nextEvidence);
            }
            if (inProgress > 1) {
                return failure(toolCall.id(), current, "PLAN_UPDATE_REJECTED",
                        "at most one plan step may be IN_PROGRESS");
            }
            if (!changed) {
                return failure(
                        toolCall.id(),
                        current,
                        "PLAN_UPDATE_REJECTED",
                        "plan update made no changes; do not use update_plan only to announce work. Execute the current "
                                + "IN_PROGRESS step first, then submit a changed status"
                );
            }
            AgentPlan next = new AgentPlan(current.goal(), nextSteps, current.acceptanceCriteria());
            return success(toolCall.id(), next, summary);
        } catch (JacksonException | IllegalArgumentException exception) {
            return failure(toolCall.id(), current, "INVALID_ARGUMENTS", "update_plan arguments are invalid");
        }
    }

    /** @return update_plan 的 JSON Schema 定义 */
    private static DeepSeekToolDefinition createDefinition() {
        Map<String, Object> step = Map.of(
                "type", "object",
                "properties", Map.of(
                        "id", Map.of("type", "string"),
                        "status", Map.of(
                                "type", "string",
                                "enum", List.of("PENDING", "IN_PROGRESS", "COMPLETED", "BLOCKED")
                        ),
                        "reasonCode", Map.of(
                                "type", "string",
                                "description", "Only for BLOCKED; omit for all other statuses"
                        ),
                        "reason", Map.of(
                                "type", "string",
                                "description", "Only for BLOCKED; omit for all other statuses"
                        ),
                        "resolution", Map.of(
                                "type", "string",
                                "description", "Only for BLOCKED; omit for all other statuses"
                        ),
                        "evidenceToolCallIds", Map.of(
                                "type", "array",
                                "description", "Optional evidence hints. The coordinator binds authoritative call ids "
                                        + "from the real tool trace, so this field may be omitted.",
                                "items", Map.of(
                                        "type", "string",
                                        "description", "An exact prior DeepSeek tool_call.id value, for example call_01_abcd"
                                )
                        )
                ),
                "required", List.of("id", "status"),
                "additionalProperties", false
        );
        return DeepSeekToolDefinition.function(
                TOOL_NAME,
                "Request a validated update to the current public execution plan. Send every plan step. Mark a step "
                        + "COMPLETED only with new successful evidence of the type required by that step. Multiple adjacent "
                        + "steps may advance in one update when each has separate matching evidence. Evidence cannot be "
                        + "reused across steps. Do not call this tool only to repeat the initial statuses; first execute "
                        + "the current IN_PROGRESS step, then submit a real status change. Evidence ids are optional hints; "
                        + "the coordinator automatically binds authoritative ids from the real tool trace. A tool failure "
                        + "normally remains IN_PROGRESS; "
                        + "use BLOCKED only for a verified external blocker with failed evidence.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "steps", Map.of("type", "array", "items", step),
                                "summary", Map.of("type", "string")
                        ),
                        "required", List.of("steps", "summary"),
                        "additionalProperties", false
                )
        );
    }

    /** @return 已按调用 ID 索引的工具证据 */
    private static Map<String, IndexedEvidence> indexEvidence(List<AgentRunResult.ToolStep> steps) {
        Map<String, IndexedEvidence> evidence = new HashMap<>();
        if (steps != null) {
            for (int index = 0; index < steps.size(); index++) {
                AgentRunResult.ToolStep step = steps.get(index);
                if (step.toolCallId() != null) {
                    evidence.put(step.toolCallId(), new IndexedEvidence(index, step));
                }
            }
        }
        return evidence;
    }

    /** @return 从单个步骤参数解析出的状态申请 */
    private static RequestedStep parseRequestedStep(JsonNode node) {
        String id = node.path("id").asText().trim();
        if (id.isBlank()) {
            throw new IllegalArgumentException("step id must not be blank");
        }
        PlanStepStatus status = PlanStepStatus.valueOf(node.path("status").asText().trim());
        List<String> evidence = new ArrayList<>();
        JsonNode evidenceNodes = node.path("evidenceToolCallIds");
        if (!evidenceNodes.isMissingNode() && !evidenceNodes.isArray()) {
            throw new IllegalArgumentException("evidenceToolCallIds must be an array");
        }
        if (evidenceNodes.isArray()) {
            evidenceNodes.forEach(value -> {
                if (!value.asText().isBlank()) {
                    evidence.add(value.asText().trim());
                }
            });
        }
        return new RequestedStep(
                id,
                status,
                List.copyOf(evidence),
                node.path("reasonCode").asText().trim(),
                node.path("reason").asText().trim(),
                node.path("resolution").asText().trim()
        );
    }

    /** @return 非法转换的说明；合法时为 {@code null} */
    private static String validateTransition(PlanStepStatus current, PlanStepStatus next) {
        if (current == next) {
            return null;
        }
        return switch (current) {
            case PENDING -> next == PlanStepStatus.IN_PROGRESS
                    || next == PlanStepStatus.COMPLETED
                    || next == PlanStepStatus.BLOCKED
                    ? null
                    : "PENDING may become IN_PROGRESS, or advance with validated completion/blocker evidence";
            case IN_PROGRESS -> next == PlanStepStatus.COMPLETED || next == PlanStepStatus.BLOCKED
                    ? null : "IN_PROGRESS may only become COMPLETED or BLOCKED";
            case BLOCKED -> next == PlanStepStatus.IN_PROGRESS ? null : "BLOCKED may only return to IN_PROGRESS";
            case COMPLETED -> "COMPLETED is terminal within the first-version static plan";
        };
    }

    /** @return 从真实轨迹选择出的完成证据或拒绝原因 */
    private static EvidenceSelection selectCompletionEvidence(
            List<String> requestedIds,
            Map<String, IndexedEvidence> evidence,
            int evidenceFromToolStep,
            PlanEvidenceType evidenceType,
            Set<String> usedEvidenceIds
    ) {
        if (requestedIds.stream().anyMatch(usedEvidenceIds::contains)) {
            return new EvidenceSelection(List.of(), "tool evidence cannot be reused across plan steps");
        }
        List<String> eligible = evidence.entrySet().stream()
                .filter(entry -> entry.getValue().index() >= evidenceFromToolStep)
                .filter(entry -> entry.getValue().step().success())
                .filter(entry -> !TOOL_NAME.equals(entry.getValue().step().toolName()))
                .filter(entry -> evidenceType.accepts(entry.getValue().step().toolName()))
                .filter(entry -> !usedEvidenceIds.contains(entry.getKey()))
                .sorted(Map.Entry.comparingByValue(
                        java.util.Comparator.comparingInt(IndexedEvidence::index)
                ))
                .map(Map.Entry::getKey)
                .toList();
        if (eligible.isEmpty()) {
            return new EvidenceSelection(
                    List.of(),
                    "COMPLETED requires new successful " + evidenceType
                            + " evidence produced after the step became IN_PROGRESS"
            );
        }
        List<String> distinctRequestedIds = requestedIds.stream().distinct().toList();
        List<String> selected = distinctRequestedIds.isEmpty() || !eligible.containsAll(distinctRequestedIds)
                ? List.of(eligible.getFirst())
                : distinctRequestedIds;
        usedEvidenceIds.addAll(selected);
        return new EvidenceSelection(selected, null);
    }

    /** @return 经错误码证据验证的 blocker 或拒绝原因 */
    private static BlockValidation validateBlocker(
            RequestedStep requested,
            Map<String, IndexedEvidence> evidence,
            int evidenceFromToolStep,
            Set<String> usedEvidenceIds
    ) {
        if (!BLOCK_REASON_CODES.contains(requested.reasonCode())) {
            return new BlockValidation(null, "unsupported blocker reasonCode");
        }
        if (requested.reason().isBlank() || requested.resolution().isBlank()) {
            return new BlockValidation(null, "BLOCKED requires reason and resolution");
        }
        Set<String> supportedCodes = BLOCK_EVIDENCE_CODES.get(requested.reasonCode());
        if (requested.evidenceIds().stream().anyMatch(usedEvidenceIds::contains)) {
            return new BlockValidation(null, "tool evidence cannot be reused across plan steps");
        }
        List<String> eligible = evidence.entrySet().stream()
                .filter(entry -> entry.getValue().index() >= evidenceFromToolStep)
                .filter(entry -> !entry.getValue().step().success())
                .filter(entry -> entry.getValue().step().error() != null)
                .filter(entry -> supportedCodes.contains(entry.getValue().step().error().code()))
                .filter(entry -> !usedEvidenceIds.contains(entry.getKey()))
                .sorted(Map.Entry.comparingByValue(
                        java.util.Comparator.comparingInt(IndexedEvidence::index)
                ))
                .map(Map.Entry::getKey)
                .toList();
        if (eligible.isEmpty()) {
            return new BlockValidation(
                    null,
                    "BLOCKED requires matching failed evidence produced after the step became IN_PROGRESS"
            );
        }
        List<String> distinctRequestedIds = requested.evidenceIds().stream().distinct().toList();
        List<String> selected = distinctRequestedIds.isEmpty()
                || !eligible.containsAll(distinctRequestedIds)
                ? eligible
                : distinctRequestedIds;
        usedEvidenceIds.addAll(selected);
        return new BlockValidation(
                new PlanBlocker(
                        requested.reasonCode(),
                        requested.reason(),
                        selected,
                        requested.resolution()
                ),
                null
        );
    }

    /** @return 成功的 update_plan Observation */
    private PlanUpdateResult success(String callId, AgentPlan plan, String summary) {
        try {
            String content = objectMapper.writeValueAsString(Map.of(
                    "success", true,
                    "summary", summary,
                    "plan", plan
            ));
            return new PlanUpdateResult(
                    plan,
                    new ToolExecutionResult(callId, TOOL_NAME, true, content, null),
                    summary
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize plan update", exception);
        }
    }

    /** @return 被拒绝的 update_plan Observation，计划保持不变 */
    private PlanUpdateResult failure(
            String callId,
            AgentPlan current,
            String code,
            String message
    ) {
        ToolExecutionResult.Error error = new ToolExecutionResult.Error(code, message, Map.of());
        try {
            String content = objectMapper.writeValueAsString(Map.of(
                    "success", false,
                    "error", Map.of("code", code, "message", message)
            ));
            return new PlanUpdateResult(
                    current,
                    new ToolExecutionResult(callId, TOOL_NAME, false, content, error),
                    message
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize plan update error", exception);
        }
    }

    /** AI 提出的单步状态、证据及可选阻塞信息。 */
    private record RequestedStep(
            String id,
            PlanStepStatus status,
            List<String> evidenceIds,
            String reasonCode,
            String reason,
            String resolution
    ) {
    }

    /** 从真实工具轨迹选择的证据，以及选择失败时的原因。 */
    private record EvidenceSelection(List<String> evidenceIds, String error) {
    }

    /** 阻塞验证结果。 */
    private record BlockValidation(PlanBlocker blocker, String error) {
    }

    /** 带工具轨迹下标的真实证据。 */
    private record IndexedEvidence(int index, AgentRunResult.ToolStep step) {
    }
}
