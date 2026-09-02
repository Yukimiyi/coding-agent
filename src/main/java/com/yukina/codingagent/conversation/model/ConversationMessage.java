package com.yukina.codingagent.conversation.model;

import java.time.Instant;

/**
 * 持久化的单条会话消息。
 *
 * @param id 单调递增的消息 ID
 * @param conversationId 所属会话 ID
 * @param role 消息角色
 * @param content 消息内容
 * @param status 执行状态
 * @param createdAt 创建时间
 */
public record ConversationMessage(
        long id,
        String conversationId,
        Role role,
        String content,
        Status status,
        Instant createdAt
) {

    /** 会话消息角色。 */
    public enum Role {
        /** 当前轮或历史中的用户请求。 */
        USER,
        /** Agent 成功回答或错误审计消息。 */
        ASSISTANT
    }

    /** 消息是否可用于后续模型上下文。 */
    public enum Status {
        /** 用户请求已持久化但 Agent 尚未完成。 */
        PENDING,
        /** 完整轮次成功，可进入后续模型上下文。 */
        SUCCESS,
        /** 当前轮失败，仅用于界面展示和审计。 */
        ERROR
    }
}
