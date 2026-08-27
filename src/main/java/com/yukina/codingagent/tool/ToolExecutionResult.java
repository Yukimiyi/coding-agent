package com.yukina.codingagent.tool;

import com.yukina.codingagent.deepseek.DeepSeekMessage;

public record ToolExecutionResult(
        String toolCallId,
        String toolName,
        boolean success,
        String content,
        Error error
) {

    public DeepSeekMessage toToolMessage() {
        return DeepSeekMessage.tool(toolCallId, content);
    }

    public record Error(String code, String message) {
    }
}
