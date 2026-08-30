package com.yukina.codingagent.controller;

import com.yukina.codingagent.agent.run.AgentRunHistory;
import com.yukina.codingagent.agent.run.AgentRunHistoryRepository;
import com.yukina.codingagent.conversation.model.Conversation;
import com.yukina.codingagent.conversation.model.MessagePage;
import com.yukina.codingagent.conversation.service.ConversationService;
import com.yukina.codingagent.workspace.model.Workspace;
import com.yukina.codingagent.workspace.service.WorkspaceService;
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
    private final WorkspaceService workspaceService;
    private final AgentRunHistoryRepository runHistoryRepository;

    /** 创建会话管理控制器。 */
    public ConversationController(
            ConversationService conversationService,
            WorkspaceService workspaceService,
            AgentRunHistoryRepository runHistoryRepository
    ) {
        this.conversationService = conversationService;
        this.workspaceService = workspaceService;
        this.runHistoryRepository = runHistoryRepository;
    }

    /** 创建一个新会话。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Conversation create(@RequestBody(required = false) CreateConversationRequest request) {
        String workspaceId = request == null ? null : request.workspaceId();
        String title = request == null ? null : request.title();
        if (workspaceId != null && !workspaceId.isBlank()) {
            Workspace workspace = workspaceService.get(workspaceId);
            workspaceId = workspace.id();
        }
        return conversationService.create(title, workspaceId);
    }

    /** 按最近更新时间倒序列出会话。 */
    @GetMapping
    public List<Conversation> list(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String workspaceId,
            @RequestParam(defaultValue = "false") boolean withoutWorkspace
    ) {
        if (withoutWorkspace) {
            if (workspaceId != null && !workspaceId.isBlank()) {
                throw new IllegalArgumentException("workspaceId and withoutWorkspace cannot be combined");
            }
            return conversationService.listWithoutWorkspace(limit);
        }
        if (workspaceId == null || workspaceId.isBlank()) {
            return conversationService.list(limit);
        }
        workspaceService.get(workspaceId);
        return conversationService.list(workspaceId, limit);
    }

    /** 查询指定会话。 */
    @GetMapping("/{conversationId}")
    public Conversation get(@PathVariable String conversationId) {
        return conversationService.get(conversationId);
    }

    /** 修改指定会话的标题。 */
    @PatchMapping("/{conversationId}")
    public Conversation rename(
            @PathVariable String conversationId,
            @RequestBody RenameConversationRequest request
    ) {
        return conversationService.rename(conversationId, request == null ? null : request.title());
    }

    /** 使用稳定 ID 游标分页查询历史消息。 */
    @GetMapping("/{conversationId}/messages")
    public MessagePage messages(
            @PathVariable String conversationId,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return conversationService.messages(conversationId, beforeId, limit);
    }

    /** 查询会话最近一次终态运行及其工具轨迹。 */
    @GetMapping("/{conversationId}/latest-run")
    public ResponseEntity<AgentRunHistory> latestRun(@PathVariable String conversationId) {
        conversationService.get(conversationId);
        return runHistoryRepository.findLatest(conversationId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** 删除会话、关联消息及其热缓存。 */
    @DeleteMapping("/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String conversationId) {
        conversationService.delete(conversationId);
    }

    /** 创建会话请求。 */
    public record CreateConversationRequest(String title, String workspaceId) {
    }

    /** 修改会话标题请求。 */
    public record RenameConversationRequest(String title) {
    }
}
