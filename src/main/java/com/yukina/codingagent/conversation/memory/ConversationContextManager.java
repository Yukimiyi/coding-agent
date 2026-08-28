package com.yukina.codingagent.conversation.memory;

import com.yukina.codingagent.conversation.model.ConversationMessage;
import com.yukina.codingagent.conversation.repository.ConversationRepository;
import com.yukina.codingagent.deepseek.DeepSeekMessage;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 协调数据库长期历史与热上下文窗口，并负责缓存未命中回填。
 */
@Service
public class ConversationContextManager {

    private final ConversationRepository repository;
    private final ConversationMemoryStore memoryStore;
    private final ConversationContextProperties properties;

    /** 创建上下文管理器。 */
    public ConversationContextManager(
            ConversationRepository repository,
            ConversationMemoryStore memoryStore,
            ConversationContextProperties properties
    ) {
        this.repository = repository;
        this.memoryStore = memoryStore;
        this.properties = properties;
    }

    /**
     * 优先读取热缓存；未命中时从数据库恢复最近成功消息。
     */
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

    /**
     * 持久化成功消息，并将其追加到受窗口限制的热上下文。
     */
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

    /**
     * 持久化错误消息用于审计，但不将其加入模型上下文。
     */
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

    /** 清除指定会话的热上下文。 */
    public void clear(String conversationId) {
        memoryStore.delete(conversationId);
    }

    /** 将持久化消息转换为 DeepSeek 协议消息。 */
    private DeepSeekMessage toDeepSeekMessage(ConversationMessage message) {
        String content = limitContent(message.content());
        return switch (message.role()) {
            case USER -> DeepSeekMessage.user(content);
            case ASSISTANT -> DeepSeekMessage.assistant(content, null, null);
        };
    }

    /** 限制单条消息进入模型上下文的长度。 */
    private String limitContent(String content) {
        if (content.length() <= properties.maxContentChars()) {
            return content;
        }
        return content.substring(0, properties.maxContentChars()) + "\n[message truncated]";
    }
}
