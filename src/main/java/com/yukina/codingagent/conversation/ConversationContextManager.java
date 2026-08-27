package com.yukina.codingagent.conversation;

import com.yukina.codingagent.deepseek.DeepSeekMessage;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class ConversationContextManager {

    private final ConversationRepository repository;
    private final ConversationMemoryStore memoryStore;
    private final ConversationContextProperties properties;

    public ConversationContextManager(
            ConversationRepository repository,
            ConversationMemoryStore memoryStore,
            ConversationContextProperties properties
    ) {
        this.repository = repository;
        this.memoryStore = memoryStore;
        this.properties = properties;
    }

    public List<DeepSeekMessage> load(String conversationId) {
        return memoryStore.get(conversationId).orElseGet(() -> {
            List<DeepSeekMessage> restored = repository.findRecentSuccessfulMessages(
                            conversationId,
                            properties.maxMessages()
                    ).stream()
                    .map(this::toDeepSeekMessage)
                    .toList();
            memoryStore.put(conversationId, restored);
            return restored;
        });
    }

    public ConversationMessage appendSuccess(
            String conversationId,
            ConversationMessage.Role role,
            String content
    ) {
        List<DeepSeekMessage> context = new ArrayList<>(load(conversationId));
        Instant now = Instant.now();
        ConversationMessage saved = repository.appendMessage(
                conversationId,
                role,
                content,
                ConversationMessage.Status.SUCCESS,
                now
        );
        repository.touch(conversationId, now);

        context.add(toDeepSeekMessage(saved));
        int fromIndex = Math.max(0, context.size() - properties.maxMessages());
        memoryStore.put(conversationId, context.subList(fromIndex, context.size()));
        return saved;
    }

    public ConversationMessage appendError(
            String conversationId,
            ConversationMessage.Role role,
            String content
    ) {
        Instant now = Instant.now();
        ConversationMessage saved = repository.appendMessage(
                conversationId,
                role,
                content,
                ConversationMessage.Status.ERROR,
                now
        );
        repository.touch(conversationId, now);
        return saved;
    }

    public void clear(String conversationId) {
        memoryStore.delete(conversationId);
    }

    private DeepSeekMessage toDeepSeekMessage(ConversationMessage message) {
        String content = limitContent(message.content());
        return switch (message.role()) {
            case USER -> DeepSeekMessage.user(content);
            case ASSISTANT -> DeepSeekMessage.assistant(content, null, null);
        };
    }

    private String limitContent(String content) {
        if (content.length() <= properties.maxContentChars()) {
            return content;
        }
        return content.substring(0, properties.maxContentChars()) + "\n[message truncated]";
    }
}
