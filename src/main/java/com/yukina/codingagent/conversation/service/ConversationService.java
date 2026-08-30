package com.yukina.codingagent.conversation.service;

import com.yukina.codingagent.conversation.exception.ConversationNotFoundException;
import com.yukina.codingagent.conversation.memory.ConversationContextManager;
import com.yukina.codingagent.conversation.model.Conversation;
import com.yukina.codingagent.conversation.model.MessagePage;
import com.yukina.codingagent.conversation.repository.ConversationRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 管理会话生命周期、标题规范化和历史消息分页校验。
 */
@Service
public class ConversationService {

    private static final int MAX_TITLE_LENGTH = 200;

    private final ConversationRepository repository;
    private final ConversationContextManager contextManager;

    /** 创建会话领域服务。 */
    public ConversationService(
            ConversationRepository repository,
            ConversationContextManager contextManager
    ) {
        this.repository = repository;
        this.contextManager = contextManager;
    }

    /** 创建会话；workspaceId 为空时表示不访问任何项目文件。 */
    public Conversation create(String title, String workspaceId) {
        String normalizedWorkspaceId = workspaceId == null || workspaceId.isBlank()
                ? null
                : workspaceId;
        Instant now = Instant.now();
        return repository.create(
                UUID.randomUUID().toString(),
                normalizeTitle(title),
                normalizedWorkspaceId,
                now
        );
    }

    /** 查询会话，不存在时抛出领域异常。 */
    public Conversation get(String conversationId) {
        return repository.findById(conversationId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));
    }

    /** 按最近活动时间列出限定数量的会话。 */
    public List<Conversation> list(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return repository.listRecent(limit);
    }

    /** 按项目过滤并列出最近活动的会话。 */
    public List<Conversation> list(String workspaceId, int limit) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId must not be blank");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return repository.listRecent(workspaceId, limit);
    }

    /** 列出未绑定项目的纯对话。 */
    public List<Conversation> listWithoutWorkspace(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return repository.listRecentWithoutWorkspace(limit);
    }

    /** 规范化并修改会话标题。 */
    public Conversation rename(String conversationId, String title) {
        String normalizedTitle = normalizeTitle(title);
        if (!repository.updateTitle(conversationId, normalizedTitle, Instant.now())) {
            throw new ConversationNotFoundException(conversationId);
        }
        return get(conversationId);
    }

    /** 校验分页参数并查询历史消息。 */
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

    /** 删除会话及其持久化消息和热缓存。 */
    public void delete(String conversationId) {
        if (!repository.delete(conversationId)) {
            throw new ConversationNotFoundException(conversationId);
        }
        contextManager.clear(conversationId);
    }

    /** 压缩空白并限制标题长度，空标题使用默认值。 */
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
