package com.yukina.codingagent.conversation.exception;

/**
 * 表示编程会话目录无法创建、导入、归档或删除。
 */
public class ConversationWorkspaceException extends RuntimeException {

    /**
     * 创建可安全展示的会话目录异常。
     *
     * @param message 错误说明
     */
    public ConversationWorkspaceException(String message) {
        super(message);
    }
}
