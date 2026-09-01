package com.yukina.codingagent.controller;

import com.yukina.codingagent.conversation.model.ConversationChatResult;
import com.yukina.codingagent.conversation.model.ConversationMode;
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
     *
     * @param conversationAgentService 有状态 Agent 对话服务
     */
    public AgentController(ConversationAgentService conversationAgentService) {
        this.conversationAgentService = conversationAgentService;
    }

    /**
     * 创建指定模式的会话并同步执行任务。
     *
     * @param request 会话模式和任务文本
     * @return 新会话及 Agent 执行结果
     * @throws IllegalArgumentException 请求或任务为空时抛出
     */
    @PostMapping("/run")
    @ResponseStatus(HttpStatus.OK)
    public ConversationChatResult run(@RequestBody AgentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body must not be null");
        }
        return conversationAgentService.chat(null, request.mode(), requireTask(request.task()));
    }

    /**
     * 在指定会话中执行一轮 Agent 对话；会话 ID 为空时自动创建会话。
     *
     * @param request 会话、模式和任务文本
     * @return 会话及 Agent 执行结果
     * @throws IllegalArgumentException 请求或任务为空时抛出
     */
    @PostMapping("/chat")
    @ResponseStatus(HttpStatus.OK)
    public ConversationChatResult chat(@RequestBody ConversationChatRequest request) {
        String task = requireTask(request == null ? null : request.task());
        return conversationAgentService.chat(request.conversationId(), request.mode(), task);
    }

    /**
     * 校验并返回非空任务文本。
     *
     * @param task 原始任务文本
     * @return 原任务文本
     * @throws IllegalArgumentException 任务为空白时抛出
     */
    private static String requireTask(String task) {
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("task must not be blank");
        }
        return task;
    }

    /**
     * 独立 Agent 任务请求。
     *
     * @param mode CHAT 或 CODE
     * @param task 任务文本
     */
    public record AgentRequest(ConversationMode mode, String task) {
    }

    /**
     * 有状态对话请求。
     *
     * @param conversationId 可选已有会话 ID
     * @param mode 创建新会话时使用的模式
     * @param task 当前轮任务文本
     */
    public record ConversationChatRequest(String conversationId, ConversationMode mode, String task) {
    }
}
