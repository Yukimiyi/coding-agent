package com.yukina.codingagent.agent.run;

import com.yukina.codingagent.agent.AgentLoopObserver;
import com.yukina.codingagent.agent.AgentRunCancelledException;
import com.yukina.codingagent.agent.AgentRunResult;
import com.yukina.codingagent.conversation.model.ConversationChatResult;
import com.yukina.codingagent.conversation.service.ConversationAgentService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 调度后台 AgentLoop，并管理状态查询、SSE 事件重放和任务取消。
 */
@Service
public class AgentRunService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRunService.class);

    private final ConversationAgentService conversationAgentService;
    private final AgentRunProperties properties;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Object registryMonitor = new Object();
    private final Map<String, RunState> runs = new HashMap<>();
    private final Map<String, String> requestRuns = new HashMap<>();
    private final Map<String, String> activeConversationRuns = new HashMap<>();

    /** 创建异步 Agent 任务服务。 */
    public AgentRunService(
            ConversationAgentService conversationAgentService,
            AgentRunProperties properties
    ) {
        this.conversationAgentService = conversationAgentService;
        this.properties = properties;
    }

    /**
     * 提交后台任务并立即返回运行定位信息。
     */
    public AgentRunAccepted submit(String requestedRequestId, String conversationId, String task) {
        return submit(requestedRequestId, conversationId, null, task);
    }

    /** 在指定项目中提交后台任务并立即返回运行定位信息。 */
    public AgentRunAccepted submit(
            String requestedRequestId,
            String conversationId,
            String workspaceId,
            String task
    ) {
        String requestId = normalizeRequestId(requestedRequestId);
        synchronized (registryMonitor) {
            cleanupRegistry();
            String existingRunId = requestRuns.get(requestId);
            if (existingRunId != null) {
                RunState existing = runs.get(existingRunId);
                if (existing != null) {
                    return accepted(existing);
                }
            }
            if (runs.size() >= properties.maxRuns()) {
                throw new AgentRunConflictException("Too many retained Agent runs; retry later");
            }

            ConversationAgentService.PreparedConversation prepared =
                    conversationAgentService.prepare(conversationId, workspaceId, task);
            String activeRunId = activeConversationRuns.get(prepared.conversationId());
            if (activeRunId != null) {
                RunState active = runs.get(activeRunId);
                if (active != null && !active.status.isTerminal()) {
                    throw new AgentRunConflictException(
                            "Conversation already has an active run: " + activeRunId
                    );
                }
            }

            RunState state = new RunState(UUID.randomUUID().toString(), requestId, prepared);
            runs.put(state.runId, state);
            requestRuns.put(requestId, state.runId);
            activeConversationRuns.put(prepared.conversationId(), state.runId);
            publish(state, AgentRunEventType.QUEUED, null, null, null, null, null, null, null, null, null);
            state.future = executor.submit(() -> execute(state));
            return accepted(state);
        }
    }

    /**
     * 查询指定运行的当前快照。
     */
    public AgentRunSnapshot get(String runId) {
        return requireRun(runId).snapshot();
    }

    /**
     * 查询指定会话当前尚未结束的任务；无活跃任务时返回空。
     */
    public AgentRunSnapshot findActive(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
        synchronized (registryMonitor) {
            String runId = activeConversationRuns.get(conversationId);
            RunState state = runId == null ? null : runs.get(runId);
            return state == null || state.status.isTerminal() ? null : state.snapshot();
        }
    }

    /**
     * 创建 SSE 订阅，并从指定事件序号之后重放已有事件。
     */
    public SseEmitter subscribe(String runId, long lastEventId) {
        RunState state = requireRun(runId);
        SseEmitter emitter = new SseEmitter(properties.sseTimeout().toMillis());
        emitter.onCompletion(() -> state.removeEmitter(emitter));
        emitter.onTimeout(() -> state.removeEmitter(emitter));
        emitter.onError(error -> state.removeEmitter(emitter));

        synchronized (state.monitor) {
            try {
                for (AgentRunEvent event : state.events) {
                    if (event.sequence() > lastEventId) {
                        send(emitter, event);
                    }
                }
                if (state.status.isTerminal()) {
                    emitter.complete();
                } else {
                    state.emitters.add(emitter);
                }
            } catch (IOException | IllegalStateException exception) {
                emitter.completeWithError(exception);
            }
        }
        return emitter;
    }

    /**
     * 幂等地取消运行；已经结束的任务直接返回原状态。
     */
    public AgentRunSnapshot cancel(String runId) {
        RunState state = requireRun(runId);
        state.cancelRequested.set(true);
        Future<?> future = state.future;
        if (future != null) {
            future.cancel(true);
        }
        markCancelled(state);
        return state.snapshot();
    }

    /**
     * 在应用关闭时中断全部后台任务。
     */
    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /** 在虚拟线程中执行会话任务并生成终态事件。 */
    private void execute(RunState state) {
        try {
            state.cancelRequested();
            synchronized (state.monitor) {
                if (state.status.isTerminal()) {
                    return;
                }
                state.status = AgentRunStatus.RUNNING;
                state.startedAt = Instant.now();
            }
            publish(state, AgentRunEventType.RUNNING, null, null, null, null, null, null, null, null, null);
            publish(
                    state,
                    AgentRunEventType.PERCEPTION,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "已接收任务，正在加载会话与工作空间上下文",
                    null
            );

            ConversationChatResult chatResult = conversationAgentService.execute(
                    state.prepared,
                    observer(state),
                    state::isCancellationRequested
            );
            markCompleted(state, chatResult.result());
        } catch (AgentRunCancelledException exception) {
            markCancelled(state);
        } catch (RuntimeException exception) {
            if (state.isCancellationRequested()) {
                markCancelled(state);
            } else {
                LOGGER.error("Agent run {} failed", state.runId, exception);
                markFailed(state, "Agent execution failed");
            }
        }
    }

    /** 创建把 AgentLoop 阶段转换为 SSE 事件的观察器。 */
    private AgentLoopObserver observer(RunState state) {
        return new AgentLoopObserver() {
            @Override
            public void onIterationStarted(int iteration) {
                state.currentIteration = iteration;
                publish(
                        state,
                        AgentRunEventType.ITERATION_STARTED,
                        iteration,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                );
            }

            @Override
            public void onThought(int iteration, String summary) {
                publish(
                        state,
                        AgentRunEventType.THOUGHT,
                        iteration,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        summary,
                        null
                );
            }

            @Override
            public void onAnswerDelta(int iteration, String delta) {
                if (delta == null || delta.isEmpty()) {
                    return;
                }
                synchronized (state.monitor) {
                    state.liveContent.append(delta);
                }
                publish(
                        state,
                        AgentRunEventType.ANSWER_DELTA,
                        iteration,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        delta,
                        null
                );
            }

            @Override
            public void onAnswerReset(int iteration) {
                synchronized (state.monitor) {
                    state.liveContent.setLength(0);
                }
                publish(
                        state,
                        AgentRunEventType.ANSWER_RESET,
                        iteration,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                );
            }

            @Override
            public void onModelResponse(int iteration, String model, int toolCallCount) {
                publish(
                        state,
                        AgentRunEventType.MODEL_RESPONSE,
                        iteration,
                        model,
                        toolCallCount,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                );
            }

            @Override
            public void onToolStarted(
                    int iteration,
                    String toolCallId,
                    String toolName,
                    String arguments
            ) {
                publish(
                        state,
                        AgentRunEventType.TOOL_STARTED,
                        iteration,
                        null,
                        null,
                        toolCallId,
                        toolName,
                        arguments,
                        null,
                        null,
                        null
                );
            }

            @Override
            public void onToolCompleted(AgentRunResult.ToolStep toolStep) {
                synchronized (state.monitor) {
                    state.toolSteps.add(toolStep);
                }
                publish(
                        state,
                        AgentRunEventType.TOOL_COMPLETED,
                        toolStep.iteration(),
                        null,
                        null,
                        toolStep.toolCallId(),
                        toolStep.toolName(),
                        toolStep.arguments(),
                        toolStep,
                        null,
                        null
                );
            }
        };
    }

    /** 将正常执行结果写入状态并推送完成事件。 */
    private void markCompleted(RunState state, AgentRunResult result) {
        synchronized (state.monitor) {
            if (state.status.isTerminal()) {
                return;
            }
            state.status = AgentRunStatus.COMPLETED;
            state.result = result;
            if (state.liveContent.isEmpty() && result.answer() != null) {
                state.liveContent.append(result.answer());
            }
            state.finishedAt = Instant.now();
        }
        publish(state, AgentRunEventType.COMPLETED, state.currentIteration, null, null, null, null, null, null, null, result);
        releaseActiveConversation(state);
    }

    /** 将失败写入状态并推送安全的错误说明。 */
    private void markFailed(RunState state, String error) {
        synchronized (state.monitor) {
            if (state.status.isTerminal()) {
                return;
            }
            state.status = AgentRunStatus.FAILED;
            state.error = error;
            state.finishedAt = Instant.now();
        }
        publish(state, AgentRunEventType.FAILED, state.currentIteration, null, null, null, null, null, null, error, null);
        releaseActiveConversation(state);
    }

    /** 将取消写入状态，并保证终态事件只生成一次。 */
    private void markCancelled(RunState state) {
        synchronized (state.monitor) {
            if (state.status.isTerminal()) {
                return;
            }
            state.status = AgentRunStatus.CANCELLED;
            state.error = "Agent execution cancelled";
            state.finishedAt = Instant.now();
        }
        publish(
                state,
                AgentRunEventType.CANCELLED,
                state.currentIteration,
                null,
                null,
                null,
                null,
                null,
                null,
                state.error,
                null
        );
        releaseActiveConversation(state);
    }

    /** 创建、保存并推送一条事件。 */
    private AgentRunEvent publish(
            RunState state,
            AgentRunEventType type,
            Integer iteration,
            String model,
            Integer toolCallCount,
            String toolCallId,
            String toolName,
            String arguments,
            AgentRunResult.ToolStep toolStep,
            String message,
            AgentRunResult result
    ) {
        synchronized (state.monitor) {
            AgentRunEvent event = new AgentRunEvent(
                    ++state.sequence,
                    state.runId,
                    Instant.now(),
                    type,
                    state.status,
                    iteration,
                    model,
                    toolCallCount,
                    toolCallId,
                    toolName,
                    arguments,
                    toolStep,
                    result,
                    message
            );
            state.events.add(event);
            Iterator<SseEmitter> iterator = state.emitters.iterator();
            while (iterator.hasNext()) {
                SseEmitter emitter = iterator.next();
                try {
                    send(emitter, event);
                    if (state.status.isTerminal()) {
                        emitter.complete();
                        iterator.remove();
                    }
                } catch (IOException | IllegalStateException exception) {
                    emitter.completeWithError(exception);
                    iterator.remove();
                }
            }
            return event;
        }
    }

    /** 发送带可恢复序号的标准 SSE 消息。 */
    private static void send(SseEmitter emitter, AgentRunEvent event) throws IOException {
        emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).reconnectTime(1000).data(event));
    }

    /** 查找运行或抛出统一的不存在异常。 */
    private RunState requireRun(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        synchronized (registryMonitor) {
            cleanupRegistry();
            RunState state = runs.get(runId);
            if (state == null) {
                throw new AgentRunNotFoundException(runId);
            }
            return state;
        }
    }

    /** 构造提交响应中的接口地址。 */
    private static AgentRunAccepted accepted(RunState state) {
        String base = "/api/agent/runs/" + state.runId;
        return new AgentRunAccepted(
                state.runId,
                state.prepared.conversationId(),
                state.prepared.workspaceId(),
                state.prepared.created(),
                state.status,
                base,
                base + "/events"
        );
    }

    /** 生成或校验客户端幂等请求 ID。 */
    private static String normalizeRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String normalized = requestId.trim();
        if (normalized.length() > 100) {
            throw new IllegalArgumentException("requestId must not exceed 100 characters");
        }
        return normalized;
    }

    /** 在任务终止后释放会话活跃运行索引。 */
    private void releaseActiveConversation(RunState state) {
        synchronized (registryMonitor) {
            activeConversationRuns.remove(state.prepared.conversationId(), state.runId);
        }
    }

    /** 清理超期运行，并在数量超限时优先移除最旧终态。 */
    private void cleanupRegistry() {
        Instant cutoff = Instant.now().minus(properties.retention());
        List<RunState> removable = runs.values().stream()
                .filter(state -> state.status.isTerminal())
                .sorted(Comparator.comparing(state -> state.finishedAt))
                .toList();
        for (RunState state : removable) {
            if (state.finishedAt.isBefore(cutoff) || runs.size() >= properties.maxRuns()) {
                runs.remove(state.runId);
                requestRuns.remove(state.requestId, state.runId);
                activeConversationRuns.remove(state.prepared.conversationId(), state.runId);
            }
        }
    }

    /** 保存一个运行的可变内部状态，并通过同步快照隔离并发读写。 */
    private static final class RunState {
        private final Object monitor = new Object();
        private final String runId;
        private final String requestId;
        private final ConversationAgentService.PreparedConversation prepared;
        private final Instant createdAt = Instant.now();
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private final List<AgentRunEvent> events = new ArrayList<>();
        private final List<AgentRunResult.ToolStep> toolSteps = new ArrayList<>();
        private final StringBuilder liveContent = new StringBuilder();
        private final Set<SseEmitter> emitters = new LinkedHashSet<>();
        private volatile AgentRunStatus status = AgentRunStatus.QUEUED;
        private volatile Instant startedAt;
        private volatile Instant finishedAt;
        private volatile int currentIteration;
        private volatile AgentRunResult result;
        private volatile String error;
        private volatile long sequence;
        private volatile Future<?> future;

        /** 创建排队中的运行状态。 */
        private RunState(
                String runId,
                String requestId,
                ConversationAgentService.PreparedConversation prepared
        ) {
            this.runId = runId;
            this.requestId = requestId;
            this.prepared = prepared;
        }

        /** 判断是否已经请求取消。 */
        private boolean isCancellationRequested() {
            return cancelRequested.get() || Thread.currentThread().isInterrupted();
        }

        /** 在取消时抛出 Agent 专用异常。 */
        private void cancelRequested() {
            if (isCancellationRequested()) {
                throw new AgentRunCancelledException();
            }
        }

        /** 移除已经关闭的 SSE 订阅者。 */
        private void removeEmitter(SseEmitter emitter) {
            synchronized (monitor) {
                emitters.remove(emitter);
            }
        }

        /** 返回状态的不可变一致快照。 */
        private AgentRunSnapshot snapshot() {
            synchronized (monitor) {
                return new AgentRunSnapshot(
                        runId,
                        requestId,
                        prepared.conversationId(),
                        prepared.workspaceId(),
                        prepared.created(),
                        status,
                        createdAt,
                        startedAt,
                        finishedAt,
                        currentIteration,
                        toolSteps,
                        liveContent.toString(),
                        result,
                        error,
                        sequence
                );
            }
        }
    }
}
