package com.yukina.codingagent.agent.plan;

import com.yukina.codingagent.agent.perception.ProjectSnapshot;
import com.yukina.codingagent.agent.ResponseLanguagePolicy;
import com.yukina.codingagent.deepseek.DeepSeekChatResponse;
import com.yukina.codingagent.deepseek.DeepSeekClient;
import com.yukina.codingagent.deepseek.DeepSeekMessage;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** 使用一次不携带工具定义的 DeepSeek 调用生成 Plan-and-Solve 计划。 */
@Service
public class DeepSeekPlanningService implements PlanningService {

    /** 首次计划 JSON 无法解析时发送给模型的协议修复指令。 */
    private static final String REPAIR_INSTRUCTION =
            "Your previous response could not be parsed as the required plan. Return only one valid JSON object "
                    + "with the exact requested fields and evidenceType enum values. Do not use Markdown fences.";

    /** 执行无工具 Planner 模型调用的 DeepSeek 客户端。 */
    private final DeepSeekClient deepSeekClient;
    /** 解析 Planner 结构化 JSON 的映射器。 */
    private final ObjectMapper objectMapper;
    /** 计划步骤、输入字符和项目快照边界。 */
    private final PlanningProperties properties;

    /**
     * 创建 DeepSeek Plan-and-Solve 规划服务。
     *
     * @param deepSeekClient DeepSeek 客户端
     * @param objectMapper JSON 转换器
     * @param properties 计划和上下文上限
     */
    public DeepSeekPlanningService(
            DeepSeekClient deepSeekClient,
            ObjectMapper objectMapper,
            PlanningProperties properties
    ) {
        this.deepSeekClient = deepSeekClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** {@inheritDoc} */
    @Override
    public PlanningResult createPlan(
            String task,
            List<DeepSeekMessage> history,
            ProjectSnapshot snapshot
    ) {
        List<DeepSeekMessage> initialMessages = List.of(
                DeepSeekMessage.system(
                        properties.systemPrompt() + "\n\n" + ResponseLanguagePolicy.instructionFor(task)
                ),
                DeepSeekMessage.user(buildContext(task, history, snapshot))
        );
        DeepSeekChatResponse first = deepSeekClient.chat(initialMessages, List.of());
        try {
            return new PlanningResult(
                    parsePlan(first.firstContent(), task),
                    first.usage(),
                    false,
                    "执行计划已创建"
            );
        } catch (IllegalStateException exception) {
            List<DeepSeekMessage> repairMessages = new ArrayList<>(initialMessages);
            repairMessages.add(DeepSeekMessage.assistant(first.firstContent(), null, null));
            repairMessages.add(DeepSeekMessage.user(REPAIR_INSTRUCTION));
            DeepSeekChatResponse repaired = deepSeekClient.chat(List.copyOf(repairMessages), List.of());
            DeepSeekChatResponse.Usage usage = addUsage(first.usage(), repaired.usage());
            try {
                return new PlanningResult(
                        parsePlan(repaired.firstContent(), task),
                        usage,
                        false,
                        "执行计划已自动修复并创建"
                );
            } catch (IllegalStateException secondException) {
                return new PlanningResult(
                        fallbackPlan(task),
                        usage,
                        true,
                        "规划模型连续返回无效结构，已使用单步兜底计划"
                );
            }
        }
    }

    /**
     * 合并首次 Planner 和可选修复调用的 Token 用量。
     *
     * @param first 首次 Planner 调用用量
     * @param second JSON 修复调用用量
     * @return 两次调用的累计用量；两者都为空时返回 {@code null}
     */
    private static DeepSeekChatResponse.Usage addUsage(
            DeepSeekChatResponse.Usage first,
            DeepSeekChatResponse.Usage second
    ) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return new DeepSeekChatResponse.Usage(
                first.promptTokens() + second.promptTokens(),
                first.completionTokens() + second.completionTokens(),
                first.totalTokens() + second.totalTokens()
        );
    }

    /**
     * 在两次结构化输出均失败时生成确定性单步骤计划。
     *
     * @param task 当前用户任务
     * @return 不依赖模型结构化输出的可执行单步骤计划
     */
    private static AgentPlan fallbackPlan(String task) {
        boolean chinese = ResponseLanguagePolicy.prefersChinese(task);
        String defaultTask = chinese ? "完成用户要求的项目任务" : "Complete the requested project task";
        String normalizedTask = task == null ? defaultTask : task.strip();
        if (normalizedTask.length() > 240) {
            normalizedTask = normalizedTask.substring(0, 240) + "...";
        }
        return new AgentPlan(
                defaultTask,
                List.of(new PlanStep(
                        "step-1",
                        normalizedTask.isBlank() ? defaultTask : normalizedTask,
                        chinese
                                ? "至少获得一条能够支持最终回答的成功工具执行结果"
                                : "Produce at least one successful observable tool result supporting the final answer",
                        PlanEvidenceType.GENERAL,
                        PlanStepStatus.IN_PROGRESS,
                        0,
                        List.of(),
                        null
                )),
                List.of(chinese
                        ? "通过可观察的工具证据确认用户要求的项目任务已经完成"
                        : "The requested project task is completed with observable tool evidence")
        );
    }

    /**
     * 按总字符预算拼接 Planner 所需的任务、历史和项目快照。
     *
     * @param task 当前用户任务
     * @param history 已裁剪会话历史和可选长期摘要
     * @param snapshot 有界项目感知快照
     * @return 发送给 Planner 的分区文本
     */
    private String buildContext(String task, List<DeepSeekMessage> history, ProjectSnapshot snapshot) {
        StringBuilder context = new StringBuilder();
        append(context, "## CURRENT TASK\n" + task + "\n", properties.maxContextChars());
        append(context, "## PROJECT FILES\n" + String.join("\n", snapshot.files()) + "\n",
                properties.maxContextChars());
        append(context, "## PROJECT DESCRIPTORS\n", properties.maxContextChars());
        snapshot.descriptors().forEach((path, content) -> append(
                context,
                "### " + path + "\n" + content + "\n",
                properties.maxContextChars()
        ));
        append(context, "## EXECUTION ENVIRONMENT\n" + snapshot.environmentSummary() + "\n",
                properties.maxContextChars());
        if (history != null && !history.isEmpty()) {
            history.stream()
                    .filter(message -> "system".equals(message.role()))
                    .forEach(message -> append(
                            context,
                            "## LONG-TERM CONVERSATION MEMORY\n" + message.content() + "\n",
                            properties.maxContextChars()
                    ));
            append(context, "## RECENT CONVERSATION\n", properties.maxContextChars());
            List<DeepSeekMessage> recentTurns = history.stream()
                    .filter(message -> !"system".equals(message.role()))
                    .toList();
            recentTurns.stream().skip(Math.max(0, recentTurns.size() - 6L)).forEach(message -> append(
                        context,
                        message.role() + ": " + message.content() + "\n",
                        properties.maxContextChars()
                ));
        }
        append(
                context,
                "## OUTPUT\nReturn JSON only: {\"goal\":\"...\",\"steps\":[{\"description\":\"...\","
                        + "\"verification\":\"observable tool evidence\","
                        + "\"evidenceType\":\"INSPECTION|MUTATION|VERIFICATION\"}],"
                        + "\"acceptanceCriteria\":[\"...\"]}.\n",
                properties.maxContextChars()
        );
        return context.toString();
    }

    /**
     * 解析 Planner JSON 并规范化为带稳定步骤 ID 的计划。
     *
     * @param content Planner 返回文本
     * @param task 当前任务，用于执行语言一致性检查
     * @return 从 step-1 开始编号的不可变计划
     */
    private AgentPlan parsePlan(String content, String task) {
        try {
            JsonNode root = objectMapper.readTree(stripCodeFence(content));
            String goal = requiredText(root, "goal");
            JsonNode stepNodes = root.path("steps");
            if (!stepNodes.isArray() || stepNodes.isEmpty()) {
                throw new IllegalArgumentException("plan steps must be a non-empty array");
            }
            List<PlanStep> steps = new ArrayList<>();
            for (JsonNode node : stepNodes) {
                if (steps.size() >= properties.maxSteps()) {
                    break;
                }
                int number = steps.size() + 1;
                steps.add(new PlanStep(
                        "step-" + number,
                        requiredText(node, "description"),
                        requiredText(node, "verification"),
                        PlanEvidenceType.valueOf(requiredText(node, "evidenceType")),
                        number == 1 ? PlanStepStatus.IN_PROGRESS : PlanStepStatus.PENDING,
                        number == 1 ? 0 : -1,
                        List.of(),
                        null
                ));
            }
            List<String> criteria = new ArrayList<>();
            JsonNode criteriaNodes = root.path("acceptanceCriteria");
            if (criteriaNodes.isArray()) {
                criteriaNodes.forEach(node -> {
                    if (!node.asText().isBlank()) {
                        criteria.add(node.asText().trim());
                    }
                });
            }
            List<String> finalCriteria = criteria.isEmpty()
                    ? steps.stream().map(PlanStep::verification).toList()
                    : criteria;
            AgentPlan plan = new AgentPlan(goal, steps, finalCriteria);
            if (ResponseLanguagePolicy.requiresChineseRewrite(task, plan.toPrompt())) {
                throw new IllegalArgumentException("Chinese task requires a Chinese public plan");
            }
            return plan;
        } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
            throw new IllegalStateException("Planner returned an invalid structured plan", exception);
        }
    }

    /**
     * 读取 Planner JSON 中必须存在的非空文本字段。
     *
     * @param node Planner JSON 根节点
     * @param field 字段名称
     * @return 去除首尾空白的字段值
     */
    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("plan field must not be blank: " + field);
        }
        return value.trim();
    }

    /**
     * 去除模型偶发添加的 Markdown JSON 围栏。
     *
     * @param content Planner 原始响应
     * @return 可交给 JSON 解析器的响应文本
     */
    private static String stripCodeFence(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("planner response must not be blank");
        }
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstLineEnd = trimmed.indexOf('\n');
        int closingFence = trimmed.lastIndexOf("```");
        return firstLineEnd >= 0 && closingFence > firstLineEnd
                ? trimmed.substring(firstLineEnd + 1, closingFence).trim()
                : trimmed;
    }

    /**
     * 将文本加入剩余字符预算，超出部分直接截断。
     *
     * @param target Planner 上下文缓冲区
     * @param value 待追加文本
     * @param limit 上下文最大字符数
     */
    private static void append(StringBuilder target, String value, int limit) {
        int remaining = limit - target.length();
        if (remaining > 0 && value != null) {
            target.append(value, 0, Math.min(remaining, value.length()));
        }
    }
}
