package com.yukina.codingagent.agent.plan;

import com.yukina.codingagent.agent.AgentRunResult;
import com.yukina.codingagent.deepseek.DeepSeekToolCall;
import com.yukina.codingagent.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 update_plan 的状态机、完成证据和阻塞审批。 */
class PlanCoordinatorTest {

    private final PlanCoordinator coordinator = new PlanCoordinator(new ObjectMapper());

    /** 初始状态原样回传应提示模型先执行当前步骤，而不是把它误认为阻塞。 */
    @Test
    void rejectsNoOpWithActionableInstruction() {
        PlanUpdateResult result = coordinator.update(
                call("call-plan", """
                        {"steps":[
                        {"id":"step-1","status":"IN_PROGRESS","evidenceToolCallIds":[]},
                        {"id":"step-2","status":"PENDING","evidenceToolCallIds":[]}
                        ],"summary":"Starting work"}
                        """),
                twoStepPlan(),
                List.of()
        );

        assertFalse(result.success());
        assertTrue(result.executionResult().error().message().contains("Execute the current IN_PROGRESS step first"));
    }

    /** 成功工具证据应允许完成当前步骤并启动下一步骤。 */
    @Test
    void acceptsEvidenceBackedProgress() {
        AgentPlan initial = twoStepPlan();
        PlanUpdateResult result = coordinator.update(
                call("call-plan", """
                        {"steps":[
                          {"id":"step-1","status":"COMPLETED","evidenceToolCallIds":["call-read"]},
                          {"id":"step-2","status":"IN_PROGRESS","evidenceToolCallIds":[]}
                        ],"summary":"Inspection complete"}
                        """),
                initial,
                List.of(step("call-read", "read_file", true, null))
        );

        assertTrue(result.success(), result.executionResult().content());
        assertEquals(PlanStepStatus.COMPLETED, result.plan().steps().get(0).status());
        assertEquals(PlanStepStatus.IN_PROGRESS, result.plan().steps().get(1).status());
    }

    /** 同一工具批次完成相邻步骤时，允许带独立证据从 PENDING 直接快进。 */
    @Test
    void acceptsEvidenceBackedBatchProgress() {
        AgentPlan plan = new AgentPlan(
                "Create and verify segment tree",
                List.of(
                        new PlanStep(
                                "step-1", "Create implementation", "source exists", PlanEvidenceType.MUTATION,
                                PlanStepStatus.IN_PROGRESS, 0, List.of(), null
                        ),
                        new PlanStep(
                                "step-2", "Create tests", "tests exist", PlanEvidenceType.MUTATION,
                                PlanStepStatus.PENDING, -1, List.of(), null
                        ),
                        new PlanStep(
                                "step-3", "Compile", "compilation succeeds", PlanEvidenceType.VERIFICATION,
                                PlanStepStatus.PENDING, -1, List.of(), null
                        ),
                        new PlanStep(
                                "step-4", "Run tests", "tests pass", PlanEvidenceType.VERIFICATION,
                                PlanStepStatus.PENDING, -1, List.of(), null
                        )
                ),
                List.of("Tests pass")
        );

        PlanUpdateResult result = coordinator.update(
                call("call-plan", """
                        {"summary":"Implementation and tests created","steps":[
                        {"id":"step-1","status":"COMPLETED","reason":"implementation created"},
                        {"id":"step-2","status":"COMPLETED","reason":"tests created"},
                        {"id":"step-3","status":"IN_PROGRESS","reason":"not compiled yet"},
                        {"id":"step-4","status":"PENDING","reason":"waiting for compilation"}
                        ]}
                        """),
                plan,
                List.of(
                        step("call-write-source", "write_file", true, null),
                        step("call-write-test", "write_file", true, null)
                )
        );

        assertTrue(result.success(), result.executionResult().content());
        assertEquals(List.of("call-write-source"), result.plan().steps().get(0).evidenceToolCallIds());
        assertEquals(List.of("call-write-test"), result.plan().steps().get(1).evidenceToolCallIds());
        assertEquals(PlanStepStatus.IN_PROGRESS, result.plan().steps().get(2).status());
        assertEquals(2, result.plan().steps().get(2).evidenceFromToolStep());
    }

    /** PENDING 不能在没有匹配工具证据时伪装为已经完成。 */
    @Test
    void rejectsPendingCompletionWithoutEvidence() {
        AgentPlan plan = new AgentPlan(
                "Create source",
                List.of(new PlanStep(
                        "step-1", "Create source", "source exists", PlanEvidenceType.MUTATION,
                        PlanStepStatus.PENDING, -1, List.of(), null
                )),
                List.of("Source exists")
        );

        PlanUpdateResult result = coordinator.update(
                call("call-plan", """
                        {"summary":"Source created","steps":[{"id":"step-1","status":"COMPLETED"}]}
                        """),
                plan,
                List.of()
        );

        assertFalse(result.success());
        assertTrue(result.executionResult().error().message().contains("requires new successful MUTATION evidence"));
    }

    /** 失败工具调用不能被伪装成步骤完成证据。 */
    @Test
    void rejectsFailedCompletionEvidence() {
        PlanUpdateResult result = coordinator.update(
                call("call-plan", """
                        {"steps":[
                          {"id":"step-1","status":"COMPLETED","evidenceToolCallIds":["call-read"]},
                          {"id":"step-2","status":"PENDING","evidenceToolCallIds":[]}
                        ],"summary":"Inspection complete"}
                        """),
                twoStepPlan(),
                List.of(step(
                        "call-read",
                        "read_file",
                        false,
                        new ToolExecutionResult.Error("PATH_NOT_FOUND", "missing", Map.of())
                ))
        );

        assertFalse(result.success());
        assertEquals("PLAN_UPDATE_REJECTED", result.executionResult().error().code());
        assertEquals(PlanStepStatus.IN_PROGRESS, result.plan().steps().getFirst().status());
    }

    /** 明确的命令缺失证据允许申请 ENVIRONMENT_MISSING 阻塞。 */
    @Test
    void acceptsSupportedExternalBlocker() {
        PlanUpdateResult result = coordinator.update(
                call("call-plan", """
                        {"steps":[
                          {"id":"step-1","status":"BLOCKED","reasonCode":"ENVIRONMENT_MISSING",
                           "reason":"Compiler is unavailable","resolution":"Install javac",
                           "evidenceToolCallIds":["call-compile"]},
                          {"id":"step-2","status":"PENDING","evidenceToolCallIds":[]}
                        ],"summary":"Compilation is blocked"}
                        """),
                twoStepPlan(),
                List.of(step(
                        "call-compile",
                        "execute_command",
                        false,
                        new ToolExecutionResult.Error("COMMAND_NOT_FOUND", "javac missing", Map.of())
                ))
        );

        assertTrue(result.success());
        PlanStep blocked = result.plan().steps().getFirst();
        assertEquals(PlanStepStatus.BLOCKED, blocked.status());
        assertEquals("ENVIRONMENT_MISSING", blocked.blocker().reasonCode());
    }

    /** Reflection 的 REVISE 应只重新打开最后一步，不改写静态计划结构。 */
    @Test
    void reopensLastCompletedStepForReflectionRevision() {
        AgentPlan completed = new AgentPlan(
                "Done plan",
                List.of(
                        new PlanStep(
                                "step-1", "Edit", "edit succeeds", PlanStepStatus.COMPLETED,
                                List.of("call-edit"), null
                        ),
                        new PlanStep(
                                "step-2", "Verify", "tests pass", PlanStepStatus.COMPLETED,
                                List.of("call-test"), null
                        )
                ),
                List.of("Tests pass")
        );

        AgentPlan reopened = coordinator.reopenLastStepForRevision(completed, 2);

        assertEquals(PlanStepStatus.COMPLETED, reopened.steps().getFirst().status());
        assertEquals(PlanStepStatus.IN_PROGRESS, reopened.steps().getLast().status());
        assertTrue(reopened.steps().getLast().evidenceToolCallIds().isEmpty());
        assertEquals(2, reopened.steps().getLast().evidenceFromToolStep());
    }

    /** 步骤不能引用进入 IN_PROGRESS 之前产生的旧工具结果。 */
    @Test
    void rejectsEvidenceProducedBeforeActivation() {
        AgentPlan plan = new AgentPlan(
                "Verify",
                List.of(new PlanStep(
                        "step-1", "Run tests", "tests pass", PlanEvidenceType.VERIFICATION,
                        PlanStepStatus.IN_PROGRESS, 1, List.of(), null
                )),
                List.of("Tests pass")
        );

        PlanUpdateResult result = coordinator.update(
                call("call-plan", """
                        {"steps":[{"id":"step-1","status":"COMPLETED",
                        "evidenceToolCallIds":["call-old"]}],"summary":"Tests passed"}
                        """),
                plan,
                List.of(step("call-old", "execute_command", true, null))
        );

        assertFalse(result.success());
        assertTrue(result.executionResult().error().message().contains("after the step became IN_PROGRESS"));
    }

    /** 文件读取不能证明要求 MUTATION 证据的实现步骤已经完成。 */
    @Test
    void rejectsWrongEvidenceType() {
        AgentPlan plan = new AgentPlan(
                "Edit",
                List.of(new PlanStep(
                        "step-1", "Edit source", "edit succeeds", PlanEvidenceType.MUTATION,
                        PlanStepStatus.IN_PROGRESS, 0, List.of(), null
                )),
                List.of("Source changed")
        );

        PlanUpdateResult result = coordinator.update(
                call("call-plan", """
                        {"steps":[{"id":"step-1","status":"COMPLETED",
                        "evidenceToolCallIds":["call-read"]}],"summary":"Source changed"}
                        """),
                plan,
                List.of(step("call-read", "read_file", true, null))
        );

        assertFalse(result.success());
        assertTrue(result.executionResult().error().message().contains("MUTATION"));
    }

    /** 工具名称误作提示时，应忽略提示并绑定真实调用 ID。 */
    @Test
    void bindsActualEvidenceWhenModelUsesToolNameAsHint() {
        AgentPlan plan = new AgentPlan(
                "Create file",
                List.of(new PlanStep(
                        "step-1", "Create source", "write succeeds", PlanEvidenceType.MUTATION,
                        PlanStepStatus.IN_PROGRESS, 0, List.of(), null
                )),
                List.of("Source exists")
        );

        PlanUpdateResult result = coordinator.update(
                call("call-plan", """
                        {"steps":[{"id":"step-1","status":"COMPLETED",
                        "evidenceToolCallIds":["write_file"]}],"summary":"Source created"}
                        """),
                plan,
                List.of(step("call-write-123", "write_file", true, null))
        );

        assertTrue(result.success());
        assertEquals(List.of("call-write-123"), result.plan().steps().getFirst().evidenceToolCallIds());
    }

    /** 模型编造语义化调用 ID 时，也应由协调器替换为真实轨迹 ID。 */
    @Test
    void bindsActualEvidenceWhenModelInventsCallId() {
        AgentPlan plan = new AgentPlan(
                "Create file",
                List.of(new PlanStep(
                        "step-1", "Create source", "write succeeds", PlanEvidenceType.MUTATION,
                        PlanStepStatus.IN_PROGRESS, 0, List.of(), null
                )),
                List.of("Source exists")
        );

        PlanUpdateResult result = coordinator.update(
                call("call-plan", """
                        {"steps":[{"id":"step-1","status":"COMPLETED",
                        "evidenceToolCallIds":["call_01_segment_tree_hpp_create"]}],"summary":"Source created"}
                        """),
                plan,
                List.of(step("call_00_real_write", "write_file", true, null))
        );

        assertTrue(result.success());
        assertEquals(List.of("call_00_real_write"), result.plan().steps().getFirst().evidenceToolCallIds());
    }

    /** evidenceToolCallIds 可省略，协调器仍会绑定符合步骤类型的真实证据。 */
    @Test
    void bindsActualEvidenceWhenHintsAreOmitted() {
        AgentPlan plan = new AgentPlan(
                "Create file",
                List.of(new PlanStep(
                        "step-1", "Create source", "write succeeds", PlanEvidenceType.MUTATION,
                        PlanStepStatus.IN_PROGRESS, 0, List.of(), null
                )),
                List.of("Source exists")
        );

        PlanUpdateResult result = coordinator.update(
                call("call-plan", """
                        {"steps":[{"id":"step-1","status":"COMPLETED"}],"summary":"Source created"}
                        """),
                plan,
                List.of(step("call-write", "write_file", true, null))
        );

        assertTrue(result.success());
        assertEquals(List.of("call-write"), result.plan().steps().getFirst().evidenceToolCallIds());
    }

    /** 同一个工具调用不能同时作为两个完成步骤的证据。 */
    @Test
    void rejectsEvidenceReuseAcrossSteps() {
        AgentPlan plan = new AgentPlan(
                "Inspect",
                List.of(
                        new PlanStep(
                                "step-1", "Inspect A", "read succeeds", PlanEvidenceType.INSPECTION,
                                PlanStepStatus.COMPLETED, 0, List.of("call-read"), null
                        ),
                        new PlanStep(
                                "step-2", "Inspect B", "read succeeds", PlanEvidenceType.INSPECTION,
                                PlanStepStatus.IN_PROGRESS, 0, List.of(), null
                        )
                ),
                List.of("Both inspected")
        );

        PlanUpdateResult result = coordinator.update(
                call("call-plan", """
                        {"steps":[
                        {"id":"step-1","status":"COMPLETED","evidenceToolCallIds":["call-read"]},
                        {"id":"step-2","status":"COMPLETED","evidenceToolCallIds":["call-read"]}
                        ],"summary":"Both inspected"}
                        """),
                plan,
                List.of(step("call-read", "read_file", true, null))
        );

        assertFalse(result.success());
        assertTrue(result.executionResult().error().message().contains("cannot be reused"));
    }

    /** 创建包含一个执行中步骤和一个待执行步骤的计划。 */
    private static AgentPlan twoStepPlan() {
        return new AgentPlan(
                "Test plan",
                List.of(
                        new PlanStep(
                                "step-1", "Inspect files", "read succeeds", PlanEvidenceType.INSPECTION,
                                PlanStepStatus.IN_PROGRESS, 0, List.of(), null
                        ),
                        new PlanStep(
                                "step-2", "Run tests", "command exits zero", PlanEvidenceType.VERIFICATION,
                                PlanStepStatus.PENDING, -1, List.of(), null
                        )
                ),
                List.of("Steps have evidence")
        );
    }

    /** 创建 update_plan 工具调用。 */
    private static DeepSeekToolCall call(String id, String arguments) {
        return new DeepSeekToolCall(
                id,
                "function",
                new DeepSeekToolCall.FunctionCall(PlanCoordinator.TOOL_NAME, arguments)
        );
    }

    /** 创建一条完成或失败工具证据。 */
    private static AgentRunResult.ToolStep step(
            String id,
            String name,
            boolean success,
            ToolExecutionResult.Error error
    ) {
        return new AgentRunResult.ToolStep(
                1, id, name, "{}", false, success, "{}", false, error
        );
    }
}
