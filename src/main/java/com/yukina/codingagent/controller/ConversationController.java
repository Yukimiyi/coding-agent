package com.yukina.codingagent.controller;

import com.yukina.codingagent.agent.run.AgentRunHistory;
import com.yukina.codingagent.agent.run.AgentRunHistoryRepository;
import com.yukina.codingagent.conversation.model.Conversation;
import com.yukina.codingagent.conversation.model.ConversationMode;
import com.yukina.codingagent.conversation.model.MessagePage;
import com.yukina.codingagent.conversation.service.ConversationService;
import com.yukina.codingagent.conversation.workspace.ConversationWorkspaceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提供会话元数据和历史消息的管理接口。
 */
@RestController
@RequestMapping("/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final ConversationWorkspaceService workspaceService;
    private final AgentRunHistoryRepository runHistoryRepository;

    /**
     * 创建会话管理控制器。
     *
     * @param conversationService 会话领域服务
     * @param workspaceService CODE 会话目录状态服务
     * @param runHistoryRepository 终态运行历史仓储
     */
    public ConversationController(
            ConversationService conversationService,
            ConversationWorkspaceService workspaceService,
            AgentRunHistoryRepository runHistoryRepository
    ) {
        this.conversationService = conversationService;
        this.workspaceService = workspaceService;
        this.runHistoryRepository = runHistoryRepository;
    }

    /**
     * 创建一个新会话。
     *
     * @param request 可选标题和 CHAT/CODE 模式
     * @return 新建会话公开响应
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationResponse create(@RequestBody(required = false) CreateConversationRequest request) {
        String title = request == null ? null : request.title();
        ConversationMode mode = request == null ? ConversationMode.CHAT : request.mode();
        return response(conversationService.create(title, mode));
    }

    /**
     * 按最近更新时间倒序列出会话。
     *
     * @param limit 最大返回数量
     * @return 最近会话列表
     */
    @GetMapping
    public List<ConversationResponse> list(@RequestParam(defaultValue = "20") int limit) {
        return conversationService.list(limit).stream().map(this::response).toList();
    }

    /**
     * 查询指定会话。
     *
     * @param conversationId 会话 ID
     * @return 对应会话
     */
    @GetMapping("/{conversationId}")
    public ConversationResponse get(@PathVariable String conversationId) {
        return response(conversationService.get(conversationId));
    }

    /**
     * 修改指定会话的标题。
     *
     * @param conversationId 会话 ID
     * @param request 新标题请求
     * @return 更新后的会话
     */
    @PatchMapping("/{conversationId}")
    public ConversationResponse rename(
            @PathVariable String conversationId,
            @RequestBody RenameConversationRequest request
    ) {
        return response(conversationService.rename(conversationId, request == null ? null : request.title()));
    }

    /**
     * 使用稳定 ID 游标分页查询历史消息。
     *
     * @param conversationId 会话 ID
     * @param beforeId 可选上页最小消息 ID
     * @param limit 最大返回数量
     * @return 按时间正序展示的一页消息
     */
    @GetMapping("/{conversationId}/messages")
    public MessagePage messages(
            @PathVariable String conversationId,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return conversationService.messages(conversationId, beforeId, limit);
    }

    /**
     * 查询会话最近一次终态运行及其工具轨迹。
     *
     * @param conversationId 会话 ID
     * @return 最近运行响应，不存在历史时返回 HTTP 204
     */
    @GetMapping("/{conversationId}/latest-run")
    public ResponseEntity<AgentRunHistory> latestRun(@PathVariable String conversationId) {
        conversationService.get(conversationId);
        return runHistoryRepository.findLatest(conversationId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 删除会话、关联消息及其热缓存。
     *
     * @param conversationId 会话 ID
     */
    @DeleteMapping("/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String conversationId) {
        conversationService.delete(conversationId);
    }

    /**
     * 创建会话请求。
     *
     * @param title 可选初始标题
     * @param mode CHAT 或 CODE；为空时默认 CHAT
     */
    public record CreateConversationRequest(String title, ConversationMode mode) {
    }

    /** @param title 新会话标题 */
    public record RenameConversationRequest(String title) {
    }

    /**
     * 前端使用的会话摘要，不暴露本地目录。
     *
     * @param id 会话 ID
     * @param title 标题
     * @param mode CHAT 或 CODE
     * @param artifactAvailable CODE 会话是否已有可下载文件
     * @param createdAt 创建时间
     * @param updatedAt 最近活动时间
     */
    public record ConversationResponse(
            String id,
            String title,
            ConversationMode mode,
            boolean artifactAvailable,
            java.time.Instant createdAt,
            java.time.Instant updatedAt
    ) {
    }

    private ConversationResponse response(Conversation conversation) {
        return new ConversationResponse(
                conversation.id(),
                conversation.title(),
                conversation.mode(),
                workspaceService.hasFiles(conversation),
                conversation.createdAt(),
                conversation.updatedAt()
        );
    }
}
