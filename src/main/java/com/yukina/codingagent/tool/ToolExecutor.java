package com.yukina.codingagent.tool;

import com.yukina.codingagent.deepseek.DeepSeekToolCall;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 负责校验工具调用协议、解析参数并将异常归一化为结构化结果。
 */
@Component
public class ToolExecutor {

    /** 按模型请求的名称定位唯一工具实现。 */
    private final ToolRegistry registry;
    /** 解析工具参数并序列化统一错误结构。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建工具执行器。
     *
     * @param registry 工具名称到实现的注册表
     * @param objectMapper 参数解析和错误结果序列化器
     */
    public ToolExecutor(ToolRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行单个模型工具调用；业务失败以结果返回，不中断 Agent 循环。
     *
     * @param toolCall DeepSeek 返回的单个工具调用
     * @return 成功内容或结构化失败信息，方法不会传播工具业务异常
     */
    public ToolExecutionResult execute(DeepSeekToolCall toolCall) {
        if (toolCall == null || toolCall.function() == null) {
            return failure(null, null, "INVALID_TOOL_CALL", "Tool call and function must not be null", Map.of());
        }

        String toolCallId = toolCall.id();
        String toolName = toolCall.function().name();
        if (toolCallId == null || toolCallId.isBlank()) {
            return failure(toolCallId, toolName, "INVALID_TOOL_CALL", "Tool call id must not be blank", Map.of());
        }
        if (!"function".equals(toolCall.type())) {
            return failure(toolCallId, toolName, "UNSUPPORTED_TOOL_TYPE", "Only function tools are supported", Map.of());
        }
        if (toolName == null || toolName.isBlank()) {
            return failure(toolCallId, toolName, "INVALID_TOOL_CALL", "Tool name must not be blank", Map.of());
        }

        AgentTool tool = registry.find(toolName).orElse(null);
        if (tool == null) {
            return failure(toolCallId, toolName, "TOOL_NOT_FOUND", "Unknown tool: " + toolName, Map.of());
        }

        JsonNode arguments;
        try {
            String rawArguments = toolCall.function().arguments();
            arguments = rawArguments == null || rawArguments.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(rawArguments);
        } catch (JacksonException exception) {
            return failure(toolCallId, toolName, "INVALID_ARGUMENTS", "Tool arguments must be valid JSON", Map.of());
        }
        if (arguments == null || !arguments.isObject()) {
            return failure(toolCallId, toolName, "INVALID_ARGUMENTS", "Tool arguments must be a JSON object", Map.of());
        }

        try {
            String content = tool.execute(arguments);
            if (content == null) {
                return failure(toolCallId, toolName, "EMPTY_TOOL_RESULT", "Tool result must not be null", Map.of());
            }
            return new ToolExecutionResult(toolCallId, toolName, true, content, null);
        } catch (ToolExecutionException exception) {
            return failure(toolCallId, toolName, exception.code(), exception.getMessage(), exception.details());
        } catch (Exception exception) {
            return failure(toolCallId, toolName, "TOOL_EXECUTION_FAILED", "Tool execution failed", Map.of());
        }
    }

    /**
     * 按模型给出的顺序执行一组工具调用。
     *
     * @param toolCalls 待执行的工具调用列表
     * @return 与输入顺序一致的不可变执行结果列表
     * @throws IllegalArgumentException 列表为 {@code null} 时抛出
     */
    public List<ToolExecutionResult> executeAll(List<DeepSeekToolCall> toolCalls) {
        if (toolCalls == null) {
            throw new IllegalArgumentException("toolCalls must not be null");
        }
        return toolCalls.stream()
                .map(this::execute)
                .toList();
    }

    /**
     * 创建同时适合 API 展示和模型回传的失败结果。
     *
     * @param toolCallId 工具调用 ID，可为 {@code null}
     * @param toolName 工具名称，可为 {@code null}
     * @param code 稳定错误码
     * @param message 可读错误说明
     * @param details 可选扩展字段
     * @return 失败状态的统一工具结果
     * @throws IllegalStateException 错误结果无法序列化时抛出
     */
    private ToolExecutionResult failure(
            String toolCallId,
            String toolName,
            String code,
            String message,
            Map<String, Object> details
    ) {
        String safeMessage = message == null || message.isBlank() ? "Tool execution failed" : message;
        Map<String, Object> safeDetails = details == null ? Map.of() : Map.copyOf(details);
        ToolExecutionResult.Error error = new ToolExecutionResult.Error(code, safeMessage, safeDetails);
        Map<String, Object> errorBody = new LinkedHashMap<>();
        errorBody.put("success", false);
        Map<String, Object> serializedError = new LinkedHashMap<>();
        serializedError.put("code", code);
        serializedError.put("message", safeMessage);
        serializedError.putAll(safeDetails);
        errorBody.put("error", serializedError);
        try {
            return new ToolExecutionResult(
                    toolCallId,
                    toolName,
                    false,
                    objectMapper.writeValueAsString(errorBody),
                    error
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException("Failed to serialize tool error", exception);
        }
    }
}
