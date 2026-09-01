package com.yukina.codingagent.tool;

import com.yukina.codingagent.deepseek.DeepSeekMessage;
import com.yukina.codingagent.deepseek.DeepSeekToolCall;
import com.yukina.codingagent.deepseek.DeepSeekToolDefinition;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证工具调用协议校验和错误归一化。 */
class ToolExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 验证已注册工具可执行并转换为工具消息。 */
    @Test
    void executesRegisteredToolAndCreatesToolMessage() {
        ToolExecutor executor = executor(arguments -> {
            assertEquals("hello", arguments.path("text").asText());
            return "{\"echo\":\"hello\"}";
        });

        ToolExecutionResult result = executor.execute(call("echo", "{\"text\":\"hello\"}"));

        assertTrue(result.success());
        assertEquals("{\"echo\":\"hello\"}", result.content());
        assertNull(result.error());
        DeepSeekMessage message = result.toToolMessage();
        assertEquals("tool", message.role());
        assertEquals("call-1", message.toolCallId());
        assertEquals(result.content(), message.content());
    }

    /** 验证未知工具返回稳定失败结果。 */
    @Test
    void returnsFailureForUnknownTool() throws Exception {
        ToolExecutionResult result = executor(arguments -> "{}").execute(call("missing", "{}"));

        assertFalse(result.success());
        assertEquals("TOOL_NOT_FOUND", result.error().code());
        assertEquals("TOOL_NOT_FOUND", objectMapper.readTree(result.content()).path("error").path("code").asText());
    }

    /** 验证非法 JSON 参数不会进入工具实现。 */
    @Test
    void returnsFailureForMalformedArguments() {
        ToolExecutionResult result = executor(arguments -> "{}").execute(call("echo", "not-json"));

        assertFalse(result.success());
        assertEquals("INVALID_ARGUMENTS", result.error().code());
    }

    /** 验证参数根节点必须是 JSON 对象。 */
    @Test
    void returnsFailureWhenArgumentsAreNotAnObject() {
        ToolExecutionResult result = executor(arguments -> "{}").execute(call("echo", "[]"));

        assertFalse(result.success());
        assertEquals("INVALID_ARGUMENTS", result.error().code());
    }

    /** 验证工具主动抛出的错误码得到保留。 */
    @Test
    void preservesExpectedToolErrors() {
        ToolExecutor executor = executor(arguments -> {
            throw new ToolExecutionException("PATH_OUTSIDE_WORKSPACE", "Path is outside workspace");
        });

        ToolExecutionResult result = executor.execute(call("echo", "{}"));

        assertFalse(result.success());
        assertEquals("PATH_OUTSIDE_WORKSPACE", result.error().code());
        assertEquals("Path is outside workspace", result.error().message());
    }

    /** 验证工具错误的恢复字段同时进入结构化对象和模型可见 JSON。 */
    @Test
    void preservesToolErrorRecoveryDetails() throws Exception {
        ToolExecutor executor = executor(arguments -> {
            throw new ToolExecutionException(
                    "COMMAND_NOT_FOUND",
                    "Executable was not found: g++",
                    Map.of("executable", "g++", "recoverable", true, "installHint", "Install G++")
            );
        });

        ToolExecutionResult result = executor.execute(call("echo", "{}"));
        JsonNode content = objectMapper.readTree(result.content()).path("error");

        assertEquals("g++", result.error().details().get("executable"));
        assertEquals("g++", content.path("executable").asText());
        assertTrue(content.path("recoverable").asBoolean());
        assertEquals("Install G++", content.path("installHint").asText());
    }

    /** 验证批量工具调用保持协议顺序。 */
    @Test
    void executesMultipleCallsInProtocolOrder() {
        ToolExecutor executor = executor(arguments -> arguments.path("value").asText());

        List<ToolExecutionResult> results = executor.executeAll(List.of(
                call("echo", "{\"value\":\"first\"}"),
                new DeepSeekToolCall(
                        "call-2",
                        "function",
                        new DeepSeekToolCall.FunctionCall("echo", "{\"value\":\"second\"}")
                )
        ));

        assertEquals("call-1", results.get(0).toolCallId());
        assertEquals("first", results.get(0).content());
        assertEquals("call-2", results.get(1).toolCallId());
        assertEquals("second", results.get(1).content());
    }

    /** 使用指定动作创建测试工具执行器。 */
    private ToolExecutor executor(ToolAction action) {
        AgentTool tool = new AgentTool() {
            /** {@inheritDoc} */
            @Override
            public DeepSeekToolDefinition definition() {
                return DeepSeekToolDefinition.function(
                        "echo",
                        "Echo test input",
                        Map.of("type", "object", "properties", Map.of())
                );
            }

            /** {@inheritDoc} */
            @Override
            public String execute(JsonNode arguments) throws Exception {
                return action.execute(arguments);
            }
        };
        return new ToolExecutor(new ToolRegistry(List.of(tool)), objectMapper);
    }

    /** 创建测试工具调用。 */
    private static DeepSeekToolCall call(String name, String arguments) {
        return new DeepSeekToolCall(
                "call-1",
                "function",
                new DeepSeekToolCall.FunctionCall(name, arguments)
        );
    }

    /** 表示测试工具可替换的执行动作。 */
    @FunctionalInterface
    private interface ToolAction {
        /** 执行测试动作。 */
        String execute(JsonNode arguments) throws Exception;
    }
}
