package com.yukina.codingagent.conversation.service;

import com.yukina.codingagent.agent.AgentLoop;
import com.yukina.codingagent.agent.AgentLoopObserver;
import com.yukina.codingagent.agent.AgentRunCancellation;
import com.yukina.codingagent.agent.AgentRunCancelledException;
import com.yukina.codingagent.agent.AgentRunResult;
import com.yukina.codingagent.conversation.memory.ConversationContextManager;
import com.yukina.codingagent.conversation.model.Conversation;
import com.yukina.codingagent.conversation.model.ConversationChatResult;
import com.yukina.codingagent.conversation.model.ConversationMessage;
import com.yukina.codingagent.conversation.model.ConversationMessage;
import com.yukina.codingagent.deepseek.DeepSeekMessage;
import com.yukina.codingagent.tool.workspace.WorkspaceExecutionContext;
import com.yukina.codingagent.workspace.model.Workspace;
import com.yukina.codingagent.workspace.service.WorkspaceService;
import com.yukina.codingagent.workspace.service.WorkspaceLockManager;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 编排一轮有状态 Agent 对话，并协调会话、上下文和执行结果持久化。
 */
@Service
public class ConversationAgentService {

    private final AgentLoop agentLoop;
    private final ConversationService conversationService;
    private final ConversationContextManager contextManager;
    private final ConversationLockManager lockManager;
    private final WorkspaceService workspaceService;
    private final WorkspaceLockManager workspaceLockManager;
    private final WorkspaceExecutionContext workspaceExecutionContext;

    /** 创建有状态 Agent 对话服务。 */
    public ConversationAgentService(
            AgentLoop agentLoop,
            ConversationService conversationService,
            ConversationContextManager contextManager,
            ConversationLockManager lockManager,
            WorkspaceService workspaceService,
            WorkspaceLockManager workspaceLockManager,
            WorkspaceExecutionContext workspaceExecutionContext
    ) {
        this.agentLoop = agentLoop;
        this.conversationService = conversationService;
        this.contextManager = contextManager;
        this.lockManager = lockManager;
        this.workspaceService = workspaceService;
        this.workspaceLockManager = workspaceLockManager;
        this.workspaceExecutionContext = workspaceExecutionContext;
    }

    /**
     * 创建或加载会话，并在会话锁内执行当前任务。
     */
    public ConversationChatResult chat(String requestedConversationId, String task) {
        return chat(requestedConversationId, null, task);
    }

    /** 创建或加载指定项目中的会话，并同步执行当前任务。 */
    public ConversationChatResult chat(String requestedConversationId, String workspaceId, String task) {
        PreparedConversation prepared = prepare(requestedConversationId, workspaceId, task);
        if (prepared.workspace() == null) {
            return lockManager.withLock(
                    prepared.conversationId(),
                    () -> runConversationTurn(
                            prepared,
                            AgentLoopObserver.NONE,
                            AgentRunCancellation.NONE
                    )
            );
        }
        return workspaceExecutionContext.withWorkspace(
                workspaceService.rootPath(prepared.workspace()),
                () -> workspaceLockManager.withLock(
                        prepared.workspace().id(),
                        () -> lockManager.withLock(
                                prepared.conversationId(),
                                () -> runConversationTurn(
                                        prepared,
                                        AgentLoopObserver.NONE,
                                        AgentRunCancellation.NONE
                                )
                        )
                )
        );
    }

    /**
     * 校验任务并创建或加载会话，此步骤不执行耗时的 AgentLoop。
     */
    public PreparedConversation prepare(String requestedConversationId, String task) {
        return prepare(requestedConversationId, null, task);
    }

    /** 校验项目绑定并创建或加载会话，不执行耗时的 AgentLoop。 */
    public PreparedConversation prepare(
            String requestedConversationId,
            String requestedWorkspaceId,
            String task
    ) {
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("task must not be blank");
        }

        boolean created = requestedConversationId == null || requestedConversationId.isBlank();
        Conversation conversation;
        Workspace workspace;
        if (created) {
            workspace = requestedWorkspaceId == null || requestedWorkspaceId.isBlank()
                    ? null
                    : workspaceService.get(requestedWorkspaceId);
            conversation = conversationService.create(task, workspace == null ? null : workspace.id());
        } else {
            conversation = conversationService.get(requestedConversationId);
            workspace = conversation.workspaceId() == null
                    ? null
                    : workspaceService.get(conversation.workspaceId());
            String normalizedRequestedWorkspaceId = requestedWorkspaceId == null
                    || requestedWorkspaceId.isBlank() ? null : requestedWorkspaceId;
            if (!Objects.equals(conversation.workspaceId(), normalizedRequestedWorkspaceId)
                    && normalizedRequestedWorkspaceId != null) {
                throw new IllegalArgumentException("Conversation belongs to a different project");
            }
        }
        return new PreparedConversation(conversation.id(), created, task, workspace);
    }

    /**
     * 在可中断的会话锁中执行已准备好的异步对话任务。
     */
    public ConversationChatResult execute(
            PreparedConversation prepared,
            AgentLoopObserver observer,
            AgentRunCancellation cancellation
    ) {
        if (prepared == null) {
            throw new IllegalArgumentException("prepared conversation must not be null");
        }
        if (prepared.workspace() == null) {
            return lockManager.withInterruptibleLock(
                    prepared.conversationId(),
                    () -> runConversationTurn(prepared, observer, cancellation)
            );
        }
        return workspaceExecutionContext.withWorkspace(
                workspaceService.rootPath(prepared.workspace()),
                () -> workspaceLockManager.withInterruptibleLock(
                        prepared.workspace().id(),
                        () -> lockManager.withInterruptibleLock(
                                prepared.conversationId(),
                                () -> runConversationTurn(prepared, observer, cancellation)
                        )
                )
        );
    }

    /**
     * 加载旧上下文、保存当前用户消息、执行 Agent 并记录最终状态。
     */
    private ConversationChatResult runConversationTurn(
            PreparedConversation prepared,
            AgentLoopObserver observer,
            AgentRunCancellation cancellation
    ) {
        String conversationId = prepared.conversationId();
        conversationService.get(conversationId);
        List<DeepSeekMessage> history = contextManager.load(conversationId);
        ConversationMessage pendingUser = contextManager.appendPendingUser(conversationId, prepared.task());

        try {
            AgentRunResult result = prepared.workspace() == null
                    ? agentLoop.runWithoutTools(prepared.task(), history, observer, cancellation)
                    : agentLoop.run(prepared.task(), history, observer, cancellation);
            if (result.completed() && result.answer() != null && !result.answer().isBlank()) {
                contextManager.markSuccess(pendingUser);
                contextManager.appendSuccess(
                        conversationId,
                        ConversationMessage.Role.ASSISTANT,
                        result.answer()
                );
            } else {
                contextManager.markError(pendingUser);
                contextManager.appendError(
                        conversationId,
                        ConversationMessage.Role.ASSISTANT,
                        "Agent stopped without a final answer: " + result.stopReason()
                );
            }
            return new ConversationChatResult(conversationId, prepared.created(), result);
        } catch (AgentRunCancelledException exception) {
            contextManager.markError(pendingUser);
            contextManager.appendError(
                    conversationId,
                    ConversationMessage.Role.ASSISTANT,
                    "Agent execution cancelled"
            );
            throw exception;
        } catch (RuntimeException exception) {
            contextManager.markError(pendingUser);
            contextManager.appendError(
                    conversationId,
                    ConversationMessage.Role.ASSISTANT,
                    "Agent execution failed"
            );
            throw exception;
        }
    }

    /**
     * 已完成会话解析、可交由同步或异步执行器处理的任务。
     */
    public record PreparedConversation(
            String conversationId,
            boolean created,
            String task,
            Workspace workspace
    ) {
        /** 返回当前运行固定的工作空间 ID；纯对话返回 null。 */
        public String workspaceId() {
            return workspace == null ? null : workspace.id();
        }
    }
}
