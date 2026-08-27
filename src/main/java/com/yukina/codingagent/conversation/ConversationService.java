package com.yukina.codingagent.conversation;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ConversationService {

    private static final int MAX_TITLE_LENGTH = 200;

    private final ConversationRepository repository;
    private final ConversationContextManager contextManager;

    public ConversationService(
            ConversationRepository repository,
            ConversationContextManager contextManager
    ) {
        this.repository = repository;
        this.contextManager = contextManager;
    }

    public Conversation create(String title) {
        Instant now = Instant.now();
        return repository.create(UUID.randomUUID().toString(), normalizeTitle(title), now);
    }

    public Conversation createForTask(String task) {
        return create(task);
    }

    public Conversation get(String conversationId) {
        return repository.findById(conversationId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));
    }

    public List<Conversation> list(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return repository.listRecent(limit);
    }

    public Conversation rename(String conversationId, String title) {
        String normalizedTitle = normalizeTitle(title);
        if (!repository.updateTitle(conversationId, normalizedTitle, Instant.now())) {
            throw new ConversationNotFoundException(conversationId);
        }
        return get(conversationId);
    }

    public MessagePage messages(String conversationId, Long beforeId, int limit) {
        get(conversationId);
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException("limit must be between 1 and 50");
        }
        if (beforeId != null && beforeId <= 0) {
            throw new IllegalArgumentException("beforeId must be positive");
        }
        return repository.findMessagesBefore(conversationId, beforeId, limit);
    }

    public void delete(String conversationId) {
        if (!repository.delete(conversationId)) {
            throw new ConversationNotFoundException(conversationId);
        }
        contextManager.clear(conversationId);
    }

    private static String normalizeTitle(String title) {
        String normalized = title == null ? "" : title.trim().replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            normalized = "New conversation";
        }
        return normalized.length() <= MAX_TITLE_LENGTH
                ? normalized
                : normalized.substring(0, MAX_TITLE_LENGTH);
    }
}
