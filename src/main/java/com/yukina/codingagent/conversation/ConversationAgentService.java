package com.yukina.codingagent.conversation;

import com.yukina.codingagent.agent.AgentLoop;
import com.yukina.codingagent.agent.AgentRunResult;
import com.yukina.codingagent.deepseek.DeepSeekMessage;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ConversationAgentService {

    private final AgentLoop agentLoop;
    private final ConversationService conversationService;
    private final ConversationContextManager contextManager;
    private final ConversationLockManager lockManager;

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

    public ConversationChatResult chat(String requestedConversationId, String task) {
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("task must not be blank");
        }

        boolean created = requestedConversationId == null || requestedConversationId.isBlank();
        Conversation conversation = created
                ? conversationService.createForTask(task)
                : conversationService.get(requestedConversationId);
        return lockManager.withLock(
                conversation.id(),
                () -> runConversationTurn(conversation.id(), task, created)
        );
    }

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
