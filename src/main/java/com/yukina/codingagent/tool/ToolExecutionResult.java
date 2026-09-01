package com.yukina.codingagent.tool;

import com.yukina.codingagent.deepseek.DeepSeekMessage;

import java.util.Map;

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

    /**
     * 将执行内容转换为 DeepSeek 工具消息。
     *
     * @return 关联当前工具调用 ID 的 tool 角色消息
     */
    public DeepSeekMessage toToolMessage() {
        return DeepSeekMessage.tool(toolCallId, content);
    }

    /**
     * 工具错误的稳定错误码、可读说明和恢复信息。
     *
     * @param code 稳定机器可读错误码
     * @param message 面向模型和用户的错误说明
     * @param details 可选恢复信息
     */
    public record Error(String code, String message, Map<String, Object> details) {

        /** 规范化扩展字段，避免错误结果暴露可变集合。 */
        public Error {
            details = details == null ? Map.of() : Map.copyOf(details);
        }
    }
}
