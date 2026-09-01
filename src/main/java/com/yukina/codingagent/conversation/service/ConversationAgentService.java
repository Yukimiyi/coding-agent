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
import com.yukina.codingagent.conversation.model.ConversationMode;
import com.yukina.codingagent.conversation.workspace.ConversationWorkspaceService;
import com.yukina.codingagent.deepseek.DeepSeekMessage;
import com.yukina.codingagent.tool.workspace.WorkspaceExecutionContext;
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
    private final ConversationWorkspaceService workspaceService;
    private final WorkspaceExecutionContext workspaceExecutionContext;

    /**
     * 创建有状态 Agent 对话服务。
     *
     * @param agentLoop Agent 主循环
     * @param conversationService 会话领域服务
     * @param contextManager 持久化和热上下文管理器
     * @param lockManager 会话串行锁
     * @param workspaceService CODE 会话目录服务
     * @param workspaceExecutionContext 工具执行根目录上下文
     */
    public ConversationAgentService(
            AgentLoop agentLoop,
            ConversationService conversationService,
            ConversationContextManager contextManager,
            ConversationLockManager lockManager,
            ConversationWorkspaceService workspaceService,
            WorkspaceExecutionContext workspaceExecutionContext
    ) {
        this.agentLoop = agentLoop;
        this.conversationService = conversationService;
        this.contextManager = contextManager;
        this.lockManager = lockManager;
        this.workspaceService = workspaceService;
        this.workspaceExecutionContext = workspaceExecutionContext;
    }

    /**
     * 创建或加载会话，并在会话锁内执行当前任务。
     *
     * @param requestedConversationId 可选已有会话 ID
     * @param task 当前任务文本
     * @return 会话标识、创建状态和 Agent 结果
     */
    public ConversationChatResult chat(String requestedConversationId, String task) {
        return chat(requestedConversationId, ConversationMode.CHAT, task);
    }

    /**
     * 创建或加载指定模式的会话，并同步执行当前任务。
     *
     * @param requestedConversationId 可选已有会话 ID
     * @param mode 创建新会话时使用的模式
     * @param task 当前任务文本
     * @return 会话标识、创建状态和 Agent 结果
     */
    public ConversationChatResult chat(
            String requestedConversationId,
            ConversationMode mode,
            String task
    ) {
        PreparedConversation prepared = prepare(requestedConversationId, mode, task);
        return execute(prepared, AgentLoopObserver.NONE, AgentRunCancellation.NONE);
    }

    /**
     * 校验任务并创建或加载会话，此步骤不执行耗时的 AgentLoop。
     *
     * @param requestedConversationId 可选已有会话 ID
     * @param task 当前任务文本
     * @return 已解析的纯聊天会话执行上下文
     */
    public PreparedConversation prepare(String requestedConversationId, String task) {
        return prepare(requestedConversationId, ConversationMode.CHAT, task);
    }

    /**
     * 校验模式并创建或加载会话，不执行耗时的 AgentLoop。
     *
     * @param requestedConversationId 可选已有会话 ID
     * @param requestedMode 创建新会话时使用的模式
     * @param task 当前任务文本
     * @return 已解析的会话、创建状态和任务
     * @throws IllegalArgumentException 任务为空时抛出
     */
    public PreparedConversation prepare(
            String requestedConversationId,
            ConversationMode requestedMode,
            String task
    ) {
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("task must not be blank");
        }

        boolean created = requestedConversationId == null || requestedConversationId.isBlank();
        Conversation conversation;
        if (created) {
            conversation = conversationService.create(
                    task,
                    requestedMode == null ? ConversationMode.CHAT : requestedMode
            );
        } else {
            conversation = conversationService.get(requestedConversationId);
        }
        return new PreparedConversation(conversation, created, task);
    }

    /**
     * 在可中断的会话锁中执行已准备好的异步对话任务。
     *
     * @param prepared 已完成会话和工作空间校验的任务
     * @param observer Agent 公开阶段观察器
     * @param cancellation 协作式取消信号
     * @return 会话及 Agent 执行结果
     * @throws IllegalArgumentException 已准备任务为空时抛出
     */
    public ConversationChatResult execute(
            PreparedConversation prepared,
            AgentLoopObserver observer,
            AgentRunCancellation cancellation
    ) {
        if (prepared == null) {
            throw new IllegalArgumentException("prepared conversation must not be null");
        }
        if (prepared.conversation().mode() == ConversationMode.CHAT) {
            return lockManager.withInterruptibleLock(
                    prepared.conversationId(),
                    () -> runConversationTurn(prepared, observer, cancellation)
            );
        }
        return workspaceExecutionContext.withWorkspace(
                workspaceService.root(prepared.conversation()),
                () -> lockManager.withInterruptibleLock(
                        prepared.conversationId(),
                        () -> runConversationTurn(prepared, observer, cancellation)
                )
        );
    }

    /**
     * 加载旧上下文、保存当前用户消息、执行 Agent 并记录最终状态。
     *
     * @param prepared 已准备会话任务
     * @param observer Agent 公开阶段观察器
     * @param cancellation 协作式取消信号
     * @return 会话及 Agent 执行结果
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
            AgentRunResult result = prepared.conversation().mode() == ConversationMode.CHAT
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
     *
     * @param conversation 固定会话及其模式
     * @param created 本次请求是否创建了会话
     * @param task 当前任务文本
     */
    public record PreparedConversation(
            Conversation conversation,
            boolean created,
            String task
    ) {
        /** @return 固定会话 ID */
        public String conversationId() {
            return conversation.id();
        }

        /** @return 固定 CHAT 或 CODE 模式 */
        public ConversationMode mode() {
            return conversation.mode();
        }
    }
}
