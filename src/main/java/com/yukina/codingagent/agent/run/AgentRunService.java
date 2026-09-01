package com.yukina.codingagent.agent.run;

import com.yukina.codingagent.agent.AgentLoopObserver;
import com.yukina.codingagent.agent.AgentRunCancelledException;
import com.yukina.codingagent.agent.AgentRunResult;
import com.yukina.codingagent.conversation.model.ConversationChatResult;
import com.yukina.codingagent.conversation.model.ConversationMode;
import com.yukina.codingagent.conversation.service.ConversationAgentService;
import com.yukina.codingagent.deepseek.DeepSeekApiException;
import com.yukina.codingagent.deepseek.DeepSeekConfigurationException;
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
    private final AgentRunHistoryRepository runHistoryRepository;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Object registryMonitor = new Object();
    private final Map<String, RunState> runs = new HashMap<>();
    private final Map<String, String> requestRuns = new HashMap<>();
    private final Map<String, String> activeConversationRuns = new HashMap<>();

    /**
     * 创建异步 Agent 任务服务。
     *
     * @param conversationAgentService 会话准备与执行服务
     * @param properties 运行保留、SSE 和内存边界配置
     * @param runHistoryRepository 终态运行历史仓储
     */
    public AgentRunService(
            ConversationAgentService conversationAgentService,
            AgentRunProperties properties,
            AgentRunHistoryRepository runHistoryRepository
    ) {
        this.conversationAgentService = conversationAgentService;
        this.properties = properties;
        this.runHistoryRepository = runHistoryRepository;
    }

    /**
     * 提交后台任务并立即返回运行定位信息。
     *
     * @param requestedRequestId 可选客户端幂等请求 ID
     * @param conversationId 可选已有会话 ID
     * @param task 任务文本
     * @return 新建或同幂等键已有运行的定位信息
     */
    public AgentRunAccepted submit(String requestedRequestId, String conversationId, String task) {
        return submit(requestedRequestId, conversationId, ConversationMode.CHAT, task);
    }

    /**
     * 以指定会话模式提交后台任务并立即返回运行定位信息。
     *
     * @param requestedRequestId 可选客户端幂等请求 ID
     * @param conversationId 可选已有会话 ID
     * @param mode 创建新会话时使用的模式
     * @param task 任务文本
     * @return 新建或同幂等键已有运行的定位信息
     * @throws AgentRunConflictException 保留数量超限或会话已有活跃运行时抛出
     */
    public AgentRunAccepted submit(
            String requestedRequestId,
            String conversationId,
            ConversationMode mode,
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
                    conversationAgentService.prepare(conversationId, mode, task);
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
     *
     * @param runId 运行 ID
     * @return 并发一致的运行快照
     * @throws AgentRunNotFoundException 运行不存在或已过期时抛出
     */
    public AgentRunSnapshot get(String runId) {
        return requireRun(runId).snapshot();
    }

    /**
     * 查询指定会话当前尚未结束的任务；无活跃任务时返回空。
     *
     * @param conversationId 会话 ID
     * @return 活跃运行快照；没有活跃运行时返回 {@code null}
     * @throws IllegalArgumentException 会话 ID 为空时抛出
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
     *
     * @param runId 运行 ID
     * @param lastEventId 客户端已收到的最后事件序号
     * @return 已配置超时、回调和事件重放的 SSE 发射器
     * @throws AgentRunNotFoundException 运行不存在或已过期时抛出
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
     *
     * @param runId 运行 ID
     * @return 取消请求后的最新快照
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

    /**
     * 在虚拟线程中执行会话任务并生成终态事件。
     *
     * @param state 当前运行可变状态
     */
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
                markFailed(state, userFacingError(exception));
            }
        }
    }

    /**
     * 将内部异常转换成不泄露响应正文的可操作错误说明。
     *
     * @param exception 后台运行抛出的异常
     * @return 可安全展示给用户的错误说明
     */
    private static String userFacingError(RuntimeException exception) {
        if (exception instanceof DeepSeekConfigurationException) {
            return "DeepSeek API 密钥尚未配置";
        }
        if (exception instanceof DeepSeekApiException apiException) {
            return switch (apiException.getStatusCode()) {
                case 401, 403 -> "DeepSeek API 密钥无效或当前账户无权限";
                case 429 -> "DeepSeek 请求频率受限，请稍后重试";
                case 500, 502, 503, 504 -> "DeepSeek 服务暂时不可用，请稍后重试";
                default -> apiException.getStatusCode() > 0
                        ? "DeepSeek API 请求失败（HTTP " + apiException.getStatusCode() + "）"
                        : "无法连接 DeepSeek，或模型响应格式异常";
            };
        }
        return "Agent 执行失败";
    }

    /**
     * 创建把 AgentLoop 阶段转换为 SSE 事件的观察器。
     *
     * @param state 接收迭代、实时文本和工具轨迹的运行状态
     * @return 将所有公开阶段发布为运行事件的观察器
     */
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
            public void onProgress(int iteration, String summary) {
                publish(
                        state,
                        AgentRunEventType.PROGRESS,
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
                    int remaining = properties.maxLiveContentChars() - state.liveContent.length();
                    if (remaining > 0) {
                        state.liveContent.append(delta, 0, Math.min(remaining, delta.length()));
                    }
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

    /**
     * 将正常执行结果写入状态并推送完成事件。
     *
     * @param state 待终结运行状态
     * @param result Agent 循环结果
     */
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
        persistHistory(state);
        publish(state, AgentRunEventType.COMPLETED, state.currentIteration, null, null, null, null, null, null, null, result);
        releaseActiveConversation(state);
    }

    /**
     * 将失败写入状态并推送安全的错误说明。
     *
     * @param state 待终结运行状态
     * @param error 可公开错误说明
     */
    private void markFailed(RunState state, String error) {
        synchronized (state.monitor) {
            if (state.status.isTerminal()) {
                return;
            }
            state.status = AgentRunStatus.FAILED;
            state.error = error;
            state.finishedAt = Instant.now();
        }
        persistHistory(state);
        publish(state, AgentRunEventType.FAILED, state.currentIteration, null, null, null, null, null, null, error, null);
        releaseActiveConversation(state);
    }

    /**
     * 将取消写入状态，并保证终态事件只生成一次。
     *
     * @param state 待取消运行状态
     */
    private void markCancelled(RunState state) {
        synchronized (state.monitor) {
            if (state.status.isTerminal()) {
                return;
            }
            state.status = AgentRunStatus.CANCELLED;
            state.error = "Agent execution cancelled";
            state.finishedAt = Instant.now();
        }
        persistHistory(state);
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

    /**
     * 尽力保存终态运行；历史写入失败不改变已产生的 Agent 结果。
     *
     * @param state 已进入终态的运行状态
     */
    private void persistHistory(RunState state) {
        try {
            runHistoryRepository.save(state.snapshot());
        } catch (RuntimeException exception) {
            LOGGER.warn("Unable to persist Agent run history {}", state.runId, exception);
        }
    }

    /**
     * 创建、保存并推送一条事件。
     *
     * @param state 事件所属运行
     * @param type 事件类型
     * @param iteration 可选模型轮次
     * @param model 可选模型名称
     * @param toolCallCount 可选工具调用数量
     * @param toolCallId 可选工具调用 ID
     * @param toolName 可选工具名称
     * @param arguments 可选受限参数文本
     * @param toolStep 可选工具完成轨迹
     * @param message 可选公开消息
     * @param result 可选最终 Agent 结果
     * @return 已分配单调序号并保存的运行事件
     */
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
            while (state.events.size() > properties.maxEventsPerRun()) {
                state.events.removeFirst();
            }
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

    /**
     * 发送带可恢复序号的标准 SSE 消息。
     *
     * @param emitter 目标 SSE 连接
     * @param event 待发送运行事件
     * @throws IOException 连接写入失败时抛出
     */
    private static void send(SseEmitter emitter, AgentRunEvent event) throws IOException {
        emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).reconnectTime(1000).data(event));
    }

    /**
     * 查找运行或抛出统一的不存在异常。
     *
     * @param runId 运行 ID
     * @return 内存中的运行状态
     * @throws IllegalArgumentException ID 为空时抛出
     * @throws AgentRunNotFoundException 运行不存在或已过期时抛出
     */
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

    /**
     * 构造提交响应中的接口地址。
     *
     * @param state 已注册运行状态
     * @return 包含状态和 SSE 地址的提交响应
     */
    private static AgentRunAccepted accepted(RunState state) {
        String base = "/api/agent/runs/" + state.runId;
        return new AgentRunAccepted(
                state.runId,
                state.prepared.conversationId(),
                state.prepared.mode(),
                state.prepared.created(),
                state.status,
                base,
                base + "/events"
        );
    }

    /**
     * 生成或校验客户端幂等请求 ID。
     *
     * @param requestId 客户端提供的可选请求 ID
     * @return 去除首尾空白的请求 ID；空值时生成 UUID
     * @throws IllegalArgumentException 请求 ID 超过长度上限时抛出
     */
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

    /**
     * 在任务终止后释放会话活跃运行索引。
     *
     * @param state 已终止运行状态
     */
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

        /**
         * 创建排队中的运行状态。
         *
         * @param runId 服务端运行 ID
         * @param requestId 客户端幂等请求 ID
         * @param prepared 已完成校验的会话执行上下文
         */
        private RunState(
                String runId,
                String requestId,
                ConversationAgentService.PreparedConversation prepared
        ) {
            this.runId = runId;
            this.requestId = requestId;
            this.prepared = prepared;
        }

        /** @return 已设置取消标记或当前线程中断时返回 {@code true} */
        private boolean isCancellationRequested() {
            return cancelRequested.get() || Thread.currentThread().isInterrupted();
        }

        /** @throws AgentRunCancelledException 已请求取消时抛出 */
        private void cancelRequested() {
            if (isCancellationRequested()) {
                throw new AgentRunCancelledException();
            }
        }

        /**
         * 移除已经关闭的 SSE 订阅者。
         *
         * @param emitter 已完成、超时或失败的 SSE 发射器
         */
        private void removeEmitter(SseEmitter emitter) {
            synchronized (monitor) {
                emitters.remove(emitter);
            }
        }

        /** @return 在状态监视器内生成的不可变一致快照 */
        private AgentRunSnapshot snapshot() {
            synchronized (monitor) {
                return new AgentRunSnapshot(
                        runId,
                        requestId,
                        prepared.conversationId(),
                        prepared.mode(),
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
