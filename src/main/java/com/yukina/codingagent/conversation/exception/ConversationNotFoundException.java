package com.yukina.codingagent.conversation.exception;

/**
 * 表示请求的会话不存在或已被删除。
 */
public class ConversationNotFoundException extends RuntimeException {

    /**
     * 根据会话 ID 创建异常。
     *
     * @param conversationId 不存在的会话 ID
     */
    public ConversationNotFoundException(String conversationId) {
        super("Conversation not found: " + conversationId);
    }
}
