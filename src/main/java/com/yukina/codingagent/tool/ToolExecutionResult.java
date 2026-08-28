package com.yukina.codingagent.tool;

import com.yukina.codingagent.deepseek.DeepSeekMessage;

/**
 * 工具执行的统一结果。
 *
 * @param toolCallId 工具调用 ID
 * @param toolName 工具名称
 * @param success 是否成功
 * @param content 回传给模型的内容
 * @param error 结构化错误；成功时为空
 */
public record ToolExecutionResult(
        String toolCallId,
        String toolName,
        boolean success,
        String content,
        Error error
) {

    /** 将执行内容转换为 DeepSeek 工具消息。 */
    public DeepSeekMessage toToolMessage() {
        return DeepSeekMessage.tool(toolCallId, content);
    }

    /** 工具错误的稳定错误码和可读说明。 */
    public record Error(String code, String message) {
    }
}
