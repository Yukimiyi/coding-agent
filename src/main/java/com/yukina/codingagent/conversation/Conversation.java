package com.yukina.codingagent.conversation;

import java.time.Instant;

public record Conversation(
        String id,
        String title,
        Instant createdAt,
        Instant updatedAt
) {
}
