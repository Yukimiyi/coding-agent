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

    private final ToolRegistry registry;
    private final ObjectMapper objectMapper;

    /** 创建工具执行器。 */
    public ToolExecutor(ToolRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行单个模型工具调用；业务失败以结果返回，不中断 Agent 循环。
     */
    public ToolExecutionResult execute(DeepSeekToolCall toolCall) {
        if (toolCall == null || toolCall.function() == null) {
            return failure(null, null, "INVALID_TOOL_CALL", "Tool call and function must not be null");
        }

        String toolCallId = toolCall.id();
        String toolName = toolCall.function().name();
        if (toolCallId == null || toolCallId.isBlank()) {
            return failure(toolCallId, toolName, "INVALID_TOOL_CALL", "Tool call id must not be blank");
        }
        if (!"function".equals(toolCall.type())) {
            return failure(toolCallId, toolName, "UNSUPPORTED_TOOL_TYPE", "Only function tools are supported");
        }
        if (toolName == null || toolName.isBlank()) {
            return failure(toolCallId, toolName, "INVALID_TOOL_CALL", "Tool name must not be blank");
        }

        AgentTool tool = registry.find(toolName).orElse(null);
        if (tool == null) {
            return failure(toolCallId, toolName, "TOOL_NOT_FOUND", "Unknown tool: " + toolName);
        }

        JsonNode arguments;
        try {
            String rawArguments = toolCall.function().arguments();
            arguments = rawArguments == null || rawArguments.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(rawArguments);
        } catch (JacksonException exception) {
            return failure(toolCallId, toolName, "INVALID_ARGUMENTS", "Tool arguments must be valid JSON");
        }
        if (arguments == null || !arguments.isObject()) {
            return failure(toolCallId, toolName, "INVALID_ARGUMENTS", "Tool arguments must be a JSON object");
        }

        try {
            String content = tool.execute(arguments);
            if (content == null) {
                return failure(toolCallId, toolName, "EMPTY_TOOL_RESULT", "Tool result must not be null");
            }
            return new ToolExecutionResult(toolCallId, toolName, true, content, null);
        } catch (ToolExecutionException exception) {
            return failure(toolCallId, toolName, exception.code(), exception.getMessage());
        } catch (Exception exception) {
            return failure(toolCallId, toolName, "TOOL_EXECUTION_FAILED", "Tool execution failed");
        }
    }

    /** 按模型给出的顺序执行一组工具调用。 */
    public List<ToolExecutionResult> executeAll(List<DeepSeekToolCall> toolCalls) {
        if (toolCalls == null) {
            throw new IllegalArgumentException("toolCalls must not be null");
        }
        return toolCalls.stream()
                .map(this::execute)
                .toList();
    }

    /** 创建同时适合 API 展示和模型回传的失败结果。 */
    private ToolExecutionResult failure(String toolCallId, String toolName, String code, String message) {
        String safeMessage = message == null || message.isBlank() ? "Tool execution failed" : message;
        ToolExecutionResult.Error error = new ToolExecutionResult.Error(code, safeMessage);
        Map<String, Object> errorBody = new LinkedHashMap<>();
        errorBody.put("success", false);
        errorBody.put("error", Map.of("code", code, "message", safeMessage));
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
