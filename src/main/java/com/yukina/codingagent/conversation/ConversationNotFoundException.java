package com.yukina.codingagent.conversation;

public class ConversationNotFoundException extends RuntimeException {

    public ConversationNotFoundException(String conversationId) {
        super("Conversation not found: " + conversationId);
    }
}
