package com.yukina.codingagent.conversation;

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

@Component
public class InMemoryConversationMemoryStore implements ConversationMemoryStore {

    private final ConversationContextProperties properties;
    private final Clock clock;
    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

    @Autowired
    public InMemoryConversationMemoryStore(ConversationContextProperties properties) {
        this(properties, Clock.systemUTC());
    }

    InMemoryConversationMemoryStore(ConversationContextProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

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

    @Override
    public void delete(String conversationId) {
        entries.remove(conversationId);
    }

    private void removeExpired(Instant now) {
        entries.entrySet().removeIf(item -> !item.getValue().expiresAt().isAfter(now));
    }

    private record Entry(
            List<DeepSeekMessage> messages,
            Instant expiresAt,
            Instant lastAccess
    ) {
    }
}
