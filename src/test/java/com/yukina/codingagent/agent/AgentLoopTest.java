package com.yukina.codingagent.agent;

import com.yukina.codingagent.deepseek.DeepSeekChatResponse;
import com.yukina.codingagent.deepseek.DeepSeekClient;
import com.yukina.codingagent.deepseek.DeepSeekMessage;
import com.yukina.codingagent.deepseek.DeepSeekToolCall;
import com.yukina.codingagent.deepseek.DeepSeekToolDefinition;
import com.yukina.codingagent.tool.AgentTool;
import com.yukina.codingagent.tool.ToolExecutor;
import com.yukina.codingagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证 Agent 循环的工具编排和停止边界。 */
class AgentLoopTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 验证工具结果会回传模型并生成最终回答。 */
    @Test
    void executesToolAndReturnsFinalAnswer() throws Exception {
        DeepSeekClient client = mock(DeepSeekClient.class);
        when(client.chat(anyList(), anyList())).thenReturn(
                response(toolMessage(call("echo", "call-1", "{\"text\":\"hello\"}")), "tool_calls", 5),
                response(DeepSeekMessage.assistant("Echo completed.", null, null), "stop", 7)
        );
        AgentLoop agentLoop = loop(client, 4, 4);

        AgentRunResult result = agentLoop.run("Echo hello");

        assertTrue(result.completed());
        assertEquals(AgentRunResult.StopReason.COMPLETED, result.stopReason());
        assertEquals("Echo completed.", result.answer());
        assertEquals(2, result.iterations());
        assertEquals(1, result.toolSteps().size());
        assertTrue(result.toolSteps().getFirst().success());
        assertEquals(12, result.usage().totalTokens());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeepSeekMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(client, times(2)).chat(messages.capture(), anyList());
        List<DeepSeekMessage> secondRequest = messages.getAllValues().get(1);
        assertEquals(List.of("system", "user", "assistant", "tool"), secondRequest.stream()
                .map(DeepSeekMessage::role)
                .toList());
        assertEquals("call-1", secondRequest.get(3).toolCallId());
        assertEquals("{\"echo\":\"hello\"}", secondRequest.get(3).content());
    }

    /** 验证工具失败可作为消息反馈给模型继续恢复。 */
    @Test
    void feedsToolFailureBackToModelForRecovery() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        DeepSeekToolCall missingTool = call("missing", "call-missing", "{}");
        when(client.chat(anyList(), anyList())).thenReturn(
                response(toolMessage(missingTool), "tool_calls", 2),
                response(DeepSeekMessage.assistant("I could not use that tool.", null, null), "stop", 3)
        );

        AgentRunResult result = loop(client, 4, 4).run("Use a missing tool");

        assertTrue(result.completed());
        assertFalse(result.toolSteps().getFirst().success());
        assertEquals("TOOL_NOT_FOUND", result.toolSteps().getFirst().error().code());
    }

    /** 验证达到最大循环轮数后停止执行。 */
    @Test
    void stopsAtMaximumIterations() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        DeepSeekChatResponse repeatedCall = response(
                toolMessage(call("echo", "call-repeat", "{\"text\":\"again\"}")),
                "tool_calls",
                1
        );
        when(client.chat(anyList(), anyList())).thenReturn(repeatedCall);

        AgentRunResult result = loop(client, 2, 4).run("Keep calling tools");

        assertFalse(result.completed());
        assertEquals(AgentRunResult.StopReason.MAX_ITERATIONS, result.stopReason());
        assertEquals(2, result.iterations());
        assertEquals(2, result.toolSteps().size());
    }

    /** 验证单轮工具调用数超过上限时拒绝执行。 */
    @Test
    void refusesTooManyToolCallsInOneIteration() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        DeepSeekMessage assistant = DeepSeekMessage.assistant(null, null, List.of(
                call("echo", "call-1", "{}"),
                call("echo", "call-2", "{}")
        ));
        when(client.chat(anyList(), anyList())).thenReturn(response(assistant, "tool_calls", 1));

        AgentRunResult result = loop(client, 4, 1).run("Call too many tools");

        assertFalse(result.completed());
        assertEquals(AgentRunResult.StopReason.TOOL_CALL_LIMIT, result.stopReason());
        assertTrue(result.toolSteps().isEmpty());
    }

    /** 验证观察器按实际执行顺序收到公开阶段事件。 */
    @Test
    void publishesIterationAndToolEventsInOrder() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        when(client.chat(anyList(), anyList())).thenReturn(
                response(toolMessage(call("echo", "call-live", "{\"text\":\"live\"}")), "tool_calls", 2),
                response(DeepSeekMessage.assistant("Done.", null, null), "stop", 2)
        );
        List<String> events = new java.util.ArrayList<>();
        AgentLoopObserver observer = new AgentLoopObserver() {
            @Override
            public void onIterationStarted(int iteration) {
                events.add("iteration:" + iteration);
            }

            @Override
            public void onModelResponse(int iteration, String model, int toolCallCount) {
                events.add("model:" + iteration + ":" + toolCallCount);
            }

            @Override
            public void onToolStarted(int iteration, String id, String name, String arguments) {
                events.add("tool-start:" + name);
            }

            @Override
            public void onToolCompleted(AgentRunResult.ToolStep toolStep) {
                events.add("tool-end:" + toolStep.toolName());
            }
        };

        loop(client, 4, 4).run("Run live", List.of(), observer, AgentRunCancellation.NONE);

        assertEquals(List.of(
                "iteration:1",
                "model:1:1",
                "tool-start:echo",
                "tool-end:echo",
                "iteration:2",
                "model:2:0"
        ), events);
    }

    /** 验证预先取消的任务不会继续调用模型。 */
    @Test
    void stopsBeforeModelCallWhenCancelled() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        AtomicBoolean cancelled = new AtomicBoolean(true);

        assertThrows(
                AgentRunCancelledException.class,
                () -> loop(client, 4, 4).run("Do not run", List.of(), AgentLoopObserver.NONE, cancelled::get)
        );
        verifyNoInteractions(client);
    }

    /** 使用测试边界配置创建 Agent 循环。 */
    private AgentLoop loop(DeepSeekClient client, int maxIterations, int maxToolCalls) {
        AgentTool echo = new AgentTool() {
            /** {@inheritDoc} */
            @Override
            public DeepSeekToolDefinition definition() {
                return DeepSeekToolDefinition.function(
                        "echo",
                        "Echo text",
                        Map.of("type", "object", "properties", Map.of())
                );
            }

            /** {@inheritDoc} */
            @Override
            public String execute(JsonNode arguments) throws Exception {
                return objectMapper.writeValueAsString(Map.of("echo", arguments.path("text").asText()));
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(echo));
        ToolExecutor executor = new ToolExecutor(registry, objectMapper);
        AgentLoopProperties properties = new AgentLoopProperties(
                maxIterations,
                maxToolCalls,
                100,
                "You are a test coding agent."
        );
        return new AgentLoop(client, registry, executor, properties);
    }

    /** 创建包含单个工具调用的助手消息。 */
    private static DeepSeekMessage toolMessage(DeepSeekToolCall call) {
        return DeepSeekMessage.assistant(null, null, List.of(call));
    }

    /** 创建测试工具调用。 */
    private static DeepSeekToolCall call(String name, String id, String arguments) {
        return new DeepSeekToolCall(
                id,
                "function",
                new DeepSeekToolCall.FunctionCall(name, arguments)
        );
    }

    /** 创建测试模型响应。 */
    private static DeepSeekChatResponse response(
            DeepSeekMessage message,
            String finishReason,
            int totalTokens
    ) {
        return new DeepSeekChatResponse(
                "response-id",
                "deepseek-test",
                List.of(new DeepSeekChatResponse.Choice(0, message, finishReason)),
                new DeepSeekChatResponse.Usage(totalTokens - 1, 1, totalTokens)
        );
    }
}
