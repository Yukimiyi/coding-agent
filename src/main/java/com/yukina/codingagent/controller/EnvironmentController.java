package com.yukina.codingagent.controller;

import com.yukina.codingagent.tool.command.EnvironmentProbeService;
import com.yukina.codingagent.tool.command.EnvironmentSnapshot;
import com.yukina.codingagent.conversation.model.Conversation;
import com.yukina.codingagent.conversation.model.ConversationMode;
import com.yukina.codingagent.conversation.service.ConversationService;
import com.yukina.codingagent.conversation.workspace.ConversationWorkspaceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;

/** 提供宿主开发工具和项目 Wrapper 的只读检测接口。 */
@RestController
@RequestMapping("/environment")
public class EnvironmentController {

    private final EnvironmentProbeService environmentProbeService;
    private final ConversationService conversationService;
    private final ConversationWorkspaceService workspaceService;

    /**
     * 创建环境检测控制器。
     *
     * @param environmentProbeService 宿主环境探测服务
     * @param conversationService 会话查询服务
     * @param workspaceService CODE 会话目录服务
     */
    public EnvironmentController(
            EnvironmentProbeService environmentProbeService,
            ConversationService conversationService,
            ConversationWorkspaceService workspaceService
    ) {
        this.environmentProbeService = environmentProbeService;
        this.conversationService = conversationService;
        this.workspaceService = workspaceService;
    }

    /**
     * 返回缓存的主机能力，并按需叠加指定项目中的 Wrapper。
     *
     * @param conversationId 可选 CODE 会话 ID
     * @return 当前环境快照
     */
    @GetMapping
    public EnvironmentSnapshot inspect(@RequestParam(required = false) String conversationId) {
        return environmentProbeService.inspect(resolveWorkspaceRoot(conversationId), false);
    }

    /**
     * 重新执行主机版本检测并返回最新快照。
     *
     * @param conversationId 可选 CODE 会话 ID
     * @return 强制刷新后的环境快照
     */
    @PostMapping("/refresh")
    public EnvironmentSnapshot refresh(@RequestParam(required = false) String conversationId) {
        return environmentProbeService.refresh(resolveWorkspaceRoot(conversationId));
    }

    /**
     * 将可选会话 ID 转换为 CODE 会话项目目录。
     *
     * @param conversationId 可选会话 ID
     * @return 项目真实根目录；CHAT 或未选择会话时返回 {@code null}
     */
    private Path resolveWorkspaceRoot(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        Conversation conversation = conversationService.get(conversationId);
        return conversation.mode() == ConversationMode.CODE ? workspaceService.root(conversation) : null;
    }
}
