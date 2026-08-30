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
            restored = trimContext(restored);
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
        Instant now = Instant.now();
        ConversationMessage saved = repository.appendMessage(
                conversationId,
                role,
                content,
                ConversationMessage.Status.SUCCESS,
                now
        );
        repository.touch(conversationId, now);
        appendToContext(saved);
        return saved;
    }

    /** 先保存尚未完成的用户消息，不让它提前进入后续模型上下文。 */
    public ConversationMessage appendPendingUser(String conversationId, String content) {
        Instant now = Instant.now();
        ConversationMessage saved = repository.appendMessage(
                conversationId,
                ConversationMessage.Role.USER,
                content,
                ConversationMessage.Status.PENDING,
                now
        );
        repository.touch(conversationId, now);
        return saved;
    }

    /** 将待处理消息提交为成功并加入热上下文。 */
    public ConversationMessage markSuccess(ConversationMessage message) {
        ConversationMessage saved = updateStatus(message, ConversationMessage.Status.SUCCESS);
        appendToContext(saved);
        return saved;
    }

    /** 将当前轮用户消息标记为失败，并清除可能包含旧状态的热缓存。 */
    public ConversationMessage markError(ConversationMessage message) {
        ConversationMessage saved = updateStatus(message, ConversationMessage.Status.ERROR);
        memoryStore.delete(message.conversationId());
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

    /** 更新消息状态并返回新的不可变消息快照。 */
    private ConversationMessage updateStatus(
            ConversationMessage message,
            ConversationMessage.Status status
    ) {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        if (!repository.updateMessageStatus(message.conversationId(), message.id(), status)) {
            throw new IllegalStateException("Conversation message no longer exists: " + message.id());
        }
        return new ConversationMessage(
                message.id(),
                message.conversationId(),
                message.role(),
                message.content(),
                status,
                message.createdAt()
        );
    }

    /** 追加一条成功消息，并同时执行消息数与总字符预算裁剪。 */
    private void appendToContext(ConversationMessage message) {
        List<DeepSeekMessage> context = new ArrayList<>(load(message.conversationId()));
        context.add(toDeepSeekMessage(message));
        memoryStore.put(message.conversationId(), trimContext(context));
    }

    /** 从最近消息向前选择受总字符预算约束的完整上下文后缀。 */
    private List<DeepSeekMessage> trimContext(List<DeepSeekMessage> messages) {
        List<DeepSeekMessage> selected = new ArrayList<>();
        int totalChars = 0;
        for (int index = messages.size() - 1;
             index >= 0 && selected.size() < properties.maxMessages();
             index--) {
            DeepSeekMessage message = messages.get(index);
            int contentChars = message.content() == null ? 0 : message.content().length();
            if (!selected.isEmpty() && totalChars + contentChars > properties.maxTotalContentChars()) {
                break;
            }
            selected.addFirst(message);
            totalChars += contentChars;
        }
        while (!selected.isEmpty() && "assistant".equals(selected.getFirst().role())) {
            selected.removeFirst();
        }
        return List.copyOf(selected);
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
