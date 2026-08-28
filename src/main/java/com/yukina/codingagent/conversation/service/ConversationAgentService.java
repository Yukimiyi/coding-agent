package com.yukina.codingagent.conversation.service;

import com.yukina.codingagent.agent.AgentLoop;
import com.yukina.codingagent.agent.AgentRunResult;
import com.yukina.codingagent.conversation.memory.ConversationContextManager;
import com.yukina.codingagent.conversation.model.Conversation;
import com.yukina.codingagent.conversation.model.ConversationChatResult;
import com.yukina.codingagent.conversation.model.ConversationMessage;
import com.yukina.codingagent.deepseek.DeepSeekMessage;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 编排一轮有状态 Agent 对话，并协调会话、上下文和执行结果持久化。
 */
@Service
public class ConversationAgentService {

    private final AgentLoop agentLoop;
    private final ConversationService conversationService;
    private final ConversationContextManager contextManager;
    private final ConversationLockManager lockManager;

    /** 创建有状态 Agent 对话服务。 */
    public ConversationAgentService(
            AgentLoop agentLoop,
            ConversationService conversationService,
            ConversationContextManager contextManager,
            ConversationLockManager lockManager
    ) {
        this.agentLoop = agentLoop;
        this.conversationService = conversationService;
        this.contextManager = contextManager;
        this.lockManager = lockManager;
    }

    /**
     * 创建或加载会话，并在会话锁内执行当前任务。
     */
    public ConversationChatResult chat(String requestedConversationId, String task) {
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("task must not be blank");
        }

        boolean created = requestedConversationId == null || requestedConversationId.isBlank();
        Conversation conversation = created
                ? conversationService.create(task)
                : conversationService.get(requestedConversationId);
        return lockManager.withLock(
                conversation.id(),
                () -> runConversationTurn(conversation.id(), task, created)
        );
    }

    /**
     * 加载旧上下文、保存当前用户消息、执行 Agent 并记录最终状态。
     */
    private ConversationChatResult runConversationTurn(
            String conversationId,
            String task,
            boolean created
    ) {
        conversationService.get(conversationId);
        List<DeepSeekMessage> history = contextManager.load(conversationId);
        contextManager.appendSuccess(conversationId, ConversationMessage.Role.USER, task);

        try {
            AgentRunResult result = agentLoop.run(task, history);
            if (result.completed() && result.answer() != null && !result.answer().isBlank()) {
                contextManager.appendSuccess(
                        conversationId,
                        ConversationMessage.Role.ASSISTANT,
                        result.answer()
                );
            } else {
                contextManager.appendError(
                        conversationId,
                        ConversationMessage.Role.ASSISTANT,
                        "Agent stopped without a final answer: " + result.stopReason()
                );
            }
            return new ConversationChatResult(conversationId, created, result);
        } catch (RuntimeException exception) {
            contextManager.appendError(
                    conversationId,
                    ConversationMessage.Role.ASSISTANT,
                    "Agent execution failed"
            );
            throw exception;
        }
    }
}
