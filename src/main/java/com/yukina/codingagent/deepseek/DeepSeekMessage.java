package com.yukina.codingagent.deepseek;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * DeepSeek 对话协议中的单条消息。
 *
 * @param role 消息角色
 * @param content 可见内容
 * @param reasoningContent 推理内容
 * @param toolCalls 模型请求执行的工具调用
 * @param toolCallId 工具结果对应的调用 ID
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeepSeekMessage(
        String role,
        String content,
        @JsonProperty("reasoning_content") String reasoningContent,
        @JsonProperty("tool_calls") List<DeepSeekToolCall> toolCalls,
        @JsonProperty("tool_call_id") String toolCallId
) {

    /**
     * 校验消息角色和工具消息的关联 ID，并复制工具调用列表。
     *
     * @throws IllegalArgumentException 角色为空或工具消息缺少调用 ID 时抛出
     */
    public DeepSeekMessage {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("message role must not be blank");
        }
        toolCalls = toolCalls == null ? null : List.copyOf(toolCalls);
        if ("tool".equals(role) && (toolCallId == null || toolCallId.isBlank())) {
            throw new IllegalArgumentException("tool message must contain tool_call_id");
        }
    }

    /**
     * 创建系统消息。
     *
     * @param content 非空系统提示词
     * @return system 角色消息
     */
    public static DeepSeekMessage system(String content) {
        return text("system", content);
    }

    /**
     * 创建用户消息。
     *
     * @param content 非空用户文本
     * @return user 角色消息
     */
    public static DeepSeekMessage user(String content) {
        return text("user", content);
    }

    /**
     * 创建可包含推理内容和工具调用的助手消息。
     *
     * @param content 可公开回答文本
     * @param reasoningContent 协议中的推理文本
     * @param toolCalls 可选工具调用列表
     * @return assistant 角色消息
     */
    public static DeepSeekMessage assistant(
            String content,
            String reasoningContent,
            List<DeepSeekToolCall> toolCalls
    ) {
        return new DeepSeekMessage("assistant", content, reasoningContent, toolCalls, null);
    }

    /**
     * 创建工具执行结果消息。
     *
     * @param toolCallId 对应模型工具调用 ID
     * @param content 工具执行结果文本
     * @return tool 角色消息
     * @throws IllegalArgumentException 内容为空引用时抛出
     */
    public static DeepSeekMessage tool(String toolCallId, String content) {
        if (content == null) {
            throw new IllegalArgumentException("tool result content must not be null");
        }
        return new DeepSeekMessage("tool", content, null, null, toolCallId);
    }

    /**
     * 创建并校验普通文本消息。
     *
     * @param role system 或 user 角色
     * @param content 非空文本
     * @return 普通协议消息
     * @throws IllegalArgumentException 内容为空白时抛出
     */
    private static DeepSeekMessage text(String role, String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException(role + " message content must not be blank");
        }
        return new DeepSeekMessage(role, content, null, null, null);
    }
}
