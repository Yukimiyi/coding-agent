package com.yukina.codingagent.conversation.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 热上下文窗口和缓存生命周期配置。
 *
 * @param maxMessages 每个会话保留的最大消息数
 * @param maxContentChars 单条消息进入模型上下文的最大字符数
 * @param ttl 热缓存滑动过期时间
 * @param maxCachedConversations 内存中允许缓存的最大会话数
 */
@ConfigurationProperties(prefix = "agent.context")
public record ConversationContextProperties(
        int maxMessages,
        int maxContentChars,
        Duration ttl,
        int maxCachedConversations
) {

    /** 校验上下文窗口、缓存容量和 TTL。 */
    public ConversationContextProperties {
        if (maxMessages <= 0 || maxContentChars <= 0 || maxCachedConversations <= 0) {
            throw new IllegalArgumentException("agent context limits must be positive");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("agent.context.ttl must be positive");
        }
    }
}
