package com.yukina.codingagent.conversation;

import java.time.Instant;

public record ConversationMessage(
        long id,
        String conversationId,
        Role role,
        String content,
        Status status,
        Instant createdAt
) {

    public enum Role {
        USER,
        ASSISTANT
    }

    public enum Status {
        SUCCESS,
        ERROR
    }
}
