package com.yukina.codingagent.controller;

import com.yukina.codingagent.conversation.model.ConversationChatResult;
import com.yukina.codingagent.conversation.service.ConversationAgentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 暴露同步有状态 Agent 执行接口。
 */
@RestController
@RequestMapping("/agent")
public class AgentController {

    private final ConversationAgentService conversationAgentService;

    /**
     * 创建 Agent API 控制器。
     */
    public AgentController(ConversationAgentService conversationAgentService) {
        this.conversationAgentService = conversationAgentService;
    }

    /**
     * 在指定项目中创建会话并同步执行任务。
     */
    @PostMapping("/run")
    @ResponseStatus(HttpStatus.OK)
    public ConversationChatResult run(@RequestBody AgentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body must not be null");
        }
        return conversationAgentService.chat(null, request.workspaceId(), requireTask(request.task()));
    }

    /**
     * 在指定会话中执行一轮 Agent 对话；会话 ID 为空时自动创建会话。
     */
    @PostMapping("/chat")
    @ResponseStatus(HttpStatus.OK)
    public ConversationChatResult chat(@RequestBody ConversationChatRequest request) {
        String task = requireTask(request == null ? null : request.task());
        return conversationAgentService.chat(request.conversationId(), request.workspaceId(), task);
    }

    /**
     * 校验并返回非空任务文本。
     */
    private static String requireTask(String task) {
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("task must not be blank");
        }
        return task;
    }

    /** 独立 Agent 任务请求。 */
    public record AgentRequest(String workspaceId, String task) {
    }

    /** 有状态对话请求。 */
    public record ConversationChatRequest(String conversationId, String workspaceId, String task) {
    }
}
