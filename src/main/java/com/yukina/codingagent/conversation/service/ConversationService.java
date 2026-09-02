package com.yukina.codingagent.conversation.service;

import com.yukina.codingagent.conversation.exception.ConversationNotFoundException;
import com.yukina.codingagent.conversation.memory.ConversationContextManager;
import com.yukina.codingagent.conversation.model.Conversation;
import com.yukina.codingagent.conversation.model.ConversationMode;
import com.yukina.codingagent.conversation.model.MessagePage;
import com.yukina.codingagent.conversation.repository.ConversationRepository;
import com.yukina.codingagent.conversation.workspace.ConversationWorkspaceService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 管理会话生命周期、标题规范化和历史消息分页校验。
 */
@Service
public class ConversationService {

    /** 会话标题允许保存的最大字符数。 */
    private static final int MAX_TITLE_LENGTH = 200;

    /** 持久化会话和消息的仓储。 */
    private final ConversationRepository repository;
    /** 清理已删除会话热上下文的管理器。 */
    private final ConversationContextManager contextManager;
    /** 创建和删除 CODE 会话正式工作区的服务。 */
    private final ConversationWorkspaceService workspaceService;

    /**
     * 创建会话领域服务。
     *
     * @param repository 会话和消息仓储
     * @param contextManager 热上下文管理器
     * @param workspaceService CODE 会话目录服务
     */
    public ConversationService(
            ConversationRepository repository,
            ConversationContextManager contextManager,
            ConversationWorkspaceService workspaceService
    ) {
        this.repository = repository;
        this.contextManager = contextManager;
        this.workspaceService = workspaceService;
    }

    /**
     * 创建 CHAT 或 CODE 会话；CODE 会话同时初始化项目目录。
     *
     * @param title 可选初始标题
     * @param mode 会话模式；为空时默认 CHAT
     * @return 新建会话
     */
    public Conversation create(String title, ConversationMode mode) {
        ConversationMode normalizedMode = mode == null ? ConversationMode.CHAT : mode;
        Instant now = Instant.now();
        Conversation conversation = repository.create(
                UUID.randomUUID().toString(),
                normalizeTitle(title, normalizedMode),
                normalizedMode,
                now
        );
        if (normalizedMode == ConversationMode.CODE) {
            try {
                workspaceService.root(conversation);
            } catch (RuntimeException exception) {
                repository.delete(conversation.id());
                throw exception;
            }
        }
        return conversation;
    }

    /**
     * 查询会话，不存在时抛出领域异常。
     *
     * @param conversationId 会话 ID
     * @return 对应会话
     * @throws ConversationNotFoundException 记录不存在时抛出
     */
    public Conversation get(String conversationId) {
        return repository.findById(conversationId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));
    }

    /**
     * 按最近活动时间列出限定数量的会话。
     *
     * @param limit 返回数量，范围为 1 到 100
     * @return 最近活动会话列表
     * @throws IllegalArgumentException 数量越界时抛出
     */
    public List<Conversation> list(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return repository.listRecent(limit);
    }

    /**
     * 规范化并修改会话标题。
     *
     * @param conversationId 会话 ID
     * @param title 新标题
     * @return 更新后的会话
     * @throws ConversationNotFoundException 会话不存在时抛出
     */
    public Conversation rename(String conversationId, String title) {
        Conversation conversation = get(conversationId);
        String normalizedTitle = normalizeTitle(title, conversation.mode());
        if (!repository.updateTitle(conversationId, normalizedTitle, Instant.now())) {
            throw new ConversationNotFoundException(conversationId);
        }
        return get(conversationId);
    }

    /**
     * 校验分页参数并查询历史消息。
     *
     * @param conversationId 会话 ID
     * @param beforeId 可选上页最小消息 ID
     * @param limit 返回数量，范围为 1 到 50
     * @return 一页按时间正序排列的消息
     * @throws IllegalArgumentException 游标或数量不合法时抛出
     */
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

    /**
     * 删除会话及其持久化消息和热缓存。
     *
     * @param conversationId 会话 ID
     * @throws ConversationNotFoundException 会话不存在时抛出
     */
    public void delete(String conversationId) {
        Conversation conversation = get(conversationId);
        workspaceService.delete(conversation);
        if (!repository.delete(conversationId)) {
            throw new ConversationNotFoundException(conversationId);
        }
        contextManager.clear(conversationId);
    }

    /**
     * 压缩空白并限制标题长度，空标题使用默认值。
     *
     * @param title 原始标题
     * @param mode 会话模式，用于选择空标题默认值
     * @return 规范化且不超过长度上限的标题
     */
    private static String normalizeTitle(String title, ConversationMode mode) {
        String normalized = title == null ? "" : title.trim().replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            normalized = mode == ConversationMode.CODE ? "New coding task" : "New conversation";
        }
        return normalized.length() <= MAX_TITLE_LENGTH
                ? normalized
                : normalized.substring(0, MAX_TITLE_LENGTH);
    }
}
