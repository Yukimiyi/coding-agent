package com.yukina.codingagent.conversation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "agent.context")
public record ConversationContextProperties(
        int maxMessages,
        int maxContentChars,
        Duration ttl,
        int maxCachedConversations
) {

    public ConversationContextProperties {
        if (maxMessages <= 0 || maxContentChars <= 0 || maxCachedConversations <= 0) {
            throw new IllegalArgumentException("agent context limits must be positive");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("agent.context.ttl must be positive");
        }
    }
}
