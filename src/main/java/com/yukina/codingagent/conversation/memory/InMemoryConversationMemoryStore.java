package com.yukina.codingagent.conversation.memory;

import com.yukina.codingagent.deepseek.DeepSeekMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 基于并发 Map 的会话热上下文，实现滑动 TTL 和容量淘汰。
 */
@Component
public class InMemoryConversationMemoryStore implements ConversationMemoryStore {

    private final ConversationContextProperties properties;
    private final Clock clock;
    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

    /** 使用 UTC 系统时钟创建生产环境内存存储。 */
    @Autowired
    public InMemoryConversationMemoryStore(ConversationContextProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /**
     * 使用可注入时钟创建存储，便于精确测试 TTL 行为。
     */
    InMemoryConversationMemoryStore(ConversationContextProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 读取未过期上下文，并刷新该会话的滑动过期时间。
     */
    @Override
    public Optional<List<DeepSeekMessage>> get(String conversationId) {
        Instant now = clock.instant();
        Entry entry = entries.computeIfPresent(conversationId, (id, existing) -> {
            if (!existing.expiresAt().isAfter(now)) {
                return null;
            }
            return new Entry(
                    existing.messages(),
                    now.plus(properties.ttl()),
                    now
            );
        });
        return entry == null ? Optional.empty() : Optional.of(entry.messages());
    }

    /**
     * 写入上下文；容量已满时淘汰最久未访问的会话。
     */
    @Override
    public void put(String conversationId, List<DeepSeekMessage> messages) {
        Instant now = clock.instant();
        removeExpired(now);
        if (!entries.containsKey(conversationId)
                && entries.size() >= properties.maxCachedConversations()) {
            entries.entrySet().stream()
                    .min(Comparator.comparing(item -> item.getValue().lastAccess()))
                    .ifPresent(item -> entries.remove(item.getKey(), item.getValue()));
        }
        entries.put(
                conversationId,
                new Entry(List.copyOf(messages), now.plus(properties.ttl()), now)
        );
    }

    /** {@inheritDoc} */
    @Override
    public void delete(String conversationId) {
        entries.remove(conversationId);
    }

    /** 清理当前时刻已经过期的缓存项。 */
    private void removeExpired(Instant now) {
        entries.entrySet().removeIf(item -> !item.getValue().expiresAt().isAfter(now));
    }

    /** 保存上下文快照、过期时间和最近访问时间。 */
    private record Entry(
            List<DeepSeekMessage> messages,
            Instant expiresAt,
            Instant lastAccess
    ) {
    }
}
