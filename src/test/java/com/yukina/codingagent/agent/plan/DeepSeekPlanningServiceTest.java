package com.yukina.codingagent.agent.plan;

import com.yukina.codingagent.agent.perception.ProjectSnapshot;
import com.yukina.codingagent.deepseek.DeepSeekChatResponse;
import com.yukina.codingagent.deepseek.DeepSeekClient;
import com.yukina.codingagent.deepseek.DeepSeekMessage;
import com.yukina.codingagent.deepseek.DeepSeekToolDefinition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证 Planner 的无工具调用、项目上下文和计划规范化。 */
class DeepSeekPlanningServiceTest {

    /** Planner 输出应获得稳定步骤 ID、初始状态并受最大步骤数限制。 */
    @Test
    void createsNormalizedToolFreePlan() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        when(client.chat(anyList(), anyList())).thenReturn(response("""
                ```json
                {"goal":"Fix calculator","steps":[
                  {"description":"Inspect calculator","verification":"read_file succeeds","evidenceType":"INSPECTION"},
                  {"description":"Edit implementation","verification":"edit_file succeeds","evidenceType":"MUTATION"},
                  {"description":"Run tests","verification":"execute_command exits zero","evidenceType":"VERIFICATION"}
                ],"acceptanceCriteria":["Tests pass"]}
                ```
                """));
        DeepSeekPlanningService service = new DeepSeekPlanningService(
                client,
                new ObjectMapper(),
                new PlanningProperties(true, 2, 10000, 2, 100, 4000, "Return JSON plan.")
        );

        PlanningResult result = service.createPlan(
                "Fix add",
                List.of(DeepSeekMessage.user("The test is failing")),
                new ProjectSnapshot(
                        false,
                        List.of("pom.xml", "src/Main.java"),
                        Map.of("pom.xml", "<project/>") ,
                        "Available: java, mvn",
                        false
                )
        );

        assertEquals(2, result.plan().steps().size());
        assertEquals("step-1", result.plan().steps().getFirst().id());
        assertEquals(PlanStepStatus.IN_PROGRESS, result.plan().steps().getFirst().status());
        assertEquals(PlanStepStatus.PENDING, result.plan().steps().getLast().status());
        assertEquals(PlanEvidenceType.INSPECTION, result.plan().steps().getFirst().evidenceType());
        assertFalse(result.fallbackUsed());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeepSeekMessage>> messages = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeepSeekToolDefinition>> tools = ArgumentCaptor.forClass(List.class);
        verify(client).chat(messages.capture(), tools.capture());
        assertTrue(tools.getValue().isEmpty());
        assertTrue(messages.getValue().getLast().content().contains("src/Main.java"));
    }

    /** 首次非法结构应携带格式反馈无工具重试，并累计两次用量。 */
    @Test
    void repairsInvalidPlanOnce() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        when(client.chat(anyList(), anyList())).thenReturn(
                response("not-json"),
                response("""
                        {"goal":"Inspect project","steps":[{"description":"Read files",
                        "verification":"read_file succeeds","evidenceType":"INSPECTION"}],
                        "acceptanceCriteria":["Files inspected"]}
                        """)
        );
        DeepSeekPlanningService service = service(client);

        PlanningResult result = service.createPlan("Inspect", List.of(), snapshot());

        assertFalse(result.fallbackUsed());
        assertEquals("执行计划已自动修复并创建", result.notice());
        assertEquals(12, result.usage().totalTokens());
        verify(client, times(2)).chat(anyList(), anyList());
    }

    /** 连续两次非法结构应返回 GENERAL 单步兜底计划而不是抛出异常。 */
    @Test
    void fallsBackAfterTwoInvalidPlans() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        when(client.chat(anyList(), anyList())).thenReturn(response("bad"), response("still bad"));

        PlanningResult result = service(client).createPlan("创建 Main.java", List.of(), snapshot());

        assertTrue(result.fallbackUsed());
        assertEquals(1, result.plan().steps().size());
        assertEquals(PlanEvidenceType.GENERAL, result.plan().steps().getFirst().evidenceType());
        assertEquals("完成用户要求的项目任务", result.plan().goal());
        assertTrue(result.plan().steps().getFirst().verification().contains("工具执行结果"));
        assertTrue(result.notice().contains("兜底计划"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeepSeekMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(client, times(2)).chat(messages.capture(), anyList());
        assertTrue(messages.getAllValues().getFirst().getFirst().content().contains("Simplified Chinese"));
    }

    /** 创建使用固定边界的 Planner 服务。 */
    private static DeepSeekPlanningService service(DeepSeekClient client) {
        return new DeepSeekPlanningService(
                client,
                new ObjectMapper(),
                new PlanningProperties(true, 3, 10000, 2, 100, 4000, "Return JSON plan.")
        );
    }

    /** 创建最小项目快照。 */
    private static ProjectSnapshot snapshot() {
        return new ProjectSnapshot(true, List.of(), Map.of(), "Available: java", false);
    }

    /** 创建 Planner 模型响应。 */
    private static DeepSeekChatResponse response(String content) {
        return new DeepSeekChatResponse(
                "plan-id",
                "deepseek-test",
                List.of(new DeepSeekChatResponse.Choice(
                        0,
                        DeepSeekMessage.assistant(content, null, null),
                        "stop"
                )),
                new DeepSeekChatResponse.Usage(4, 2, 6)
        );
    }
}
