package com.yukina.codingagent.agent;

import com.yukina.codingagent.deepseek.DeepSeekChatResponse;
import com.yukina.codingagent.deepseek.DeepSeekClient;
import com.yukina.codingagent.deepseek.DeepSeekMessage;
import com.yukina.codingagent.deepseek.DeepSeekToolCall;
import com.yukina.codingagent.tool.ToolExecutionResult;
import com.yukina.codingagent.tool.ToolExecutor;
import com.yukina.codingagent.tool.ToolRegistry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 驱动“模型推理 -> 工具执行 -> 结果回传”的 Agent 主循环。
 */
@Service
public class AgentLoop {

    private static final String CHAT_ONLY_SYSTEM_PROMPT =
            "You are a coding assistant in a conversation without an attached workspace. "
                    + "Answer from the conversation context. You cannot inspect, modify, or execute local files, "
                    + "so do not claim that you performed those actions.";

    private final DeepSeekClient deepSeekClient;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final AgentLoopProperties properties;

    /**
     * 创建 Agent 循环服务。
     *
     * @param deepSeekClient DeepSeek 模型客户端
     * @param toolRegistry 可提供给模型的工具注册表
     * @param toolExecutor 工具执行器
     * @param properties 循环边界配置
     */
    public AgentLoop(
            DeepSeekClient deepSeekClient,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            AgentLoopProperties properties
    ) {
        this.deepSeekClient = deepSeekClient;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.properties = properties;
    }

    /**
     * 在无历史上下文的情况下执行一个独立任务。
     *
     * @param task 用户任务
     * @return Agent 执行结果
     */
    public AgentRunResult run(String task) {
        return run(task, List.of());
    }

    /**
     * 携带已有对话历史执行任务，直到得到最终回答或触发安全边界。
     *
     * @param task 当前用户任务
     * @param conversationHistory 仅包含 user 和 assistant 消息的历史上下文
     * @return Agent 执行结果和完整工具轨迹
     */
    public AgentRunResult run(String task, List<DeepSeekMessage> conversationHistory) {
        return run(task, conversationHistory, AgentLoopObserver.NONE, AgentRunCancellation.NONE);
    }

    /**
     * 携带观察器和取消信号执行任务。
     *
     * @param task 当前用户任务
     * @param conversationHistory 对话历史
     * @param observer 公开执行阶段观察器
     * @param cancellation 协作式取消信号
     * @return Agent 执行结果和完整工具轨迹
     */
    public AgentRunResult run(
            String task,
            List<DeepSeekMessage> conversationHistory,
            AgentLoopObserver observer,
            AgentRunCancellation cancellation
    ) {
        return run(task, conversationHistory, observer, cancellation, true);
    }

    /** 执行不附带工作空间的纯对话，不向模型提供任何本地工具。 */
    public AgentRunResult runWithoutTools(
            String task,
            List<DeepSeekMessage> conversationHistory,
            AgentLoopObserver observer,
            AgentRunCancellation cancellation
    ) {
        return run(task, conversationHistory, observer, cancellation, false);
    }

    /** 根据当前会话是否绑定工作空间执行统一的模型循环。 */
    private AgentRunResult run(
            String task,
            List<DeepSeekMessage> conversationHistory,
            AgentLoopObserver observer,
            AgentRunCancellation cancellation,
            boolean toolsEnabled
    ) {
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("task must not be blank");
        }
        List<DeepSeekMessage> safeHistory = validateHistory(conversationHistory);
        AgentLoopObserver safeObserver = observer == null ? AgentLoopObserver.NONE : observer;
        AgentRunCancellation safeCancellation = cancellation == null ? AgentRunCancellation.NONE : cancellation;

        List<DeepSeekMessage> messages = new ArrayList<>();
        messages.add(DeepSeekMessage.system(
                toolsEnabled ? properties.systemPrompt() : CHAT_ONLY_SYSTEM_PROMPT
        ));
        messages.addAll(safeHistory);
        messages.add(DeepSeekMessage.user(task));
        List<AgentRunResult.ToolStep> toolSteps = new ArrayList<>();
        Set<ToolFailureSignature> failedToolCalls = new HashSet<>();
        UsageAccumulator usage = new UsageAccumulator();
        String model = null;

        for (int iteration = 1; iteration <= properties.maxIterations(); iteration++) {
            safeCancellation.throwIfCancellationRequested();
            safeObserver.onIterationStarted(iteration);
            safeObserver.onThought(iteration, publicThoughtSummary(iteration, toolsEnabled, toolSteps));
            DeepSeekChatResponse response;
            try {
                int currentIteration = iteration;
                response = deepSeekClient.chatStream(
                        List.copyOf(messages),
                        toolsEnabled ? toolRegistry.definitions() : List.of(),
                        delta -> safeObserver.onAnswerDelta(currentIteration, delta)
                );
            } catch (RuntimeException exception) {
                safeCancellation.throwIfCancellationRequested();
                throw exception;
            }
            safeCancellation.throwIfCancellationRequested();
            model = response.model();
            usage.add(response.usage());
            DeepSeekMessage assistant = response.firstMessage();
            String finishReason = response.firstFinishReason();
            messages.add(assistant);

            List<DeepSeekToolCall> toolCalls = assistant.toolCalls() == null
                    ? List.of()
                    : assistant.toolCalls();
            if ("length".equals(finishReason)) {
                return result(
                        assistant.content(),
                        model,
                        iteration,
                        false,
                        AgentRunResult.StopReason.RESPONSE_TRUNCATED,
                        toolSteps,
                        usage
                );
            }
            if (!toolCalls.isEmpty() && assistant.content() != null && !assistant.content().isBlank()) {
                safeObserver.onAnswerReset(iteration);
            }
            safeObserver.onModelResponse(iteration, model, toolCalls.size());
            if (!toolsEnabled && !toolCalls.isEmpty()) {
                return result(
                        assistant.content(),
                        model,
                        iteration,
                        false,
                        AgentRunResult.StopReason.INVALID_TOOL_CALL,
                        toolSteps,
                        usage
                );
            }
            if (toolCalls.isEmpty()) {
                String answer = assistant.content();
                boolean completed = answer != null && !answer.isBlank();
                if (completed && finishReason != null && !"stop".equals(finishReason)) {
                    return result(
                            answer,
                            model,
                            iteration,
                            false,
                            AgentRunResult.StopReason.MODEL_STOPPED,
                            toolSteps,
                            usage
                    );
                }
                return result(
                        answer,
                        model,
                        iteration,
                        completed,
                        completed
                                ? AgentRunResult.StopReason.COMPLETED
                                : AgentRunResult.StopReason.EMPTY_RESPONSE,
                        toolSteps,
                        usage
                );
            }
            if (toolCalls.size() > properties.maxToolCallsPerIteration()) {
                return result(
                        assistant.content(),
                        model,
                        iteration,
                        false,
                        AgentRunResult.StopReason.TOOL_CALL_LIMIT,
                        toolSteps,
                        usage
                );
            }
            if (toolCalls.stream().anyMatch(call -> call == null || call.id() == null || call.id().isBlank())) {
                return result(
                        assistant.content(),
                        model,
                        iteration,
                        false,
                        AgentRunResult.StopReason.INVALID_TOOL_CALL,
                        toolSteps,
                        usage
                );
            }

            for (DeepSeekToolCall toolCall : toolCalls) {
                safeCancellation.throwIfCancellationRequested();
                String toolName = toolCall.function() == null ? null : toolCall.function().name();
                String arguments = toolCall.function() == null ? null : toolCall.function().arguments();
                safeObserver.onToolStarted(iteration, toolCall.id(), toolName, truncate(arguments).value());
                ToolExecutionResult executionResult = toolExecutor.execute(toolCall);
                safeCancellation.throwIfCancellationRequested();
                messages.add(executionResult.toToolMessage());
                AgentRunResult.ToolStep toolStep = toToolStep(iteration, toolCall, executionResult);
                toolSteps.add(toolStep);
                safeObserver.onToolCompleted(toolStep);
                if (!toolStep.success()) {
                    ToolFailureSignature signature = new ToolFailureSignature(
                            toolStep.toolName(),
                            toolStep.arguments(),
                            toolStep.error() == null ? null : toolStep.error().code()
                    );
                    if (!failedToolCalls.add(signature)) {
                        return result(
                                assistant.content(),
                                model,
                                iteration,
                                false,
                                AgentRunResult.StopReason.REPEATED_TOOL_FAILURE,
                                toolSteps,
                                usage
                        );
                    }
                }
            }
        }

        return result(
                null,
                model,
                properties.maxIterations(),
                false,
                AgentRunResult.StopReason.MAX_ITERATIONS,
                toolSteps,
                usage
        );
    }

    /**
     * 复制并校验可安全放入新一轮请求的历史消息。
     *
     * @param history 待校验历史
     * @return 不可变历史列表
     */
    private static List<DeepSeekMessage> validateHistory(List<DeepSeekMessage> history) {
        if (history == null) {
            return List.of();
        }
        List<DeepSeekMessage> safeHistory = List.copyOf(history);
        boolean containsUnsupportedRole = safeHistory.stream().anyMatch(message -> message == null
                || !("user".equals(message.role()) || "assistant".equals(message.role())));
        if (containsUnsupportedRole) {
            throw new IllegalArgumentException("conversation history may only contain user and assistant messages");
        }
        return safeHistory;
    }

    /**
     * 将工具调用及结果转换为适合 API 返回的受限轨迹记录。
     */
    private AgentRunResult.ToolStep toToolStep(
            int iteration,
            DeepSeekToolCall toolCall,
            ToolExecutionResult executionResult
    ) {
        String arguments = toolCall.function() == null ? null : toolCall.function().arguments();
        TruncatedText safeArguments = truncate(arguments);
        TruncatedText safeContent = truncate(executionResult.content());
        return new AgentRunResult.ToolStep(
                iteration,
                executionResult.toolCallId(),
                executionResult.toolName(),
                safeArguments.value(),
                safeArguments.truncated(),
                executionResult.success(),
                safeContent.value(),
                safeContent.truncated(),
                executionResult.error()
        );
    }

    /**
     * 按配置截断轨迹文本，避免响应体因大文件内容无限膨胀。
     */
    private TruncatedText truncate(String value) {
        if (value == null || value.length() <= properties.traceContentLimit()) {
            return new TruncatedText(value, false);
        }
        return new TruncatedText(value.substring(0, properties.traceContentLimit()), true);
    }

    /**
     * 统一构造不可变的 Agent 执行结果。
     */
    private static AgentRunResult result(
            String answer,
            String model,
            int iterations,
            boolean completed,
            AgentRunResult.StopReason stopReason,
            List<AgentRunResult.ToolStep> toolSteps,
            UsageAccumulator usage
    ) {
        return new AgentRunResult(
                answer,
                model,
                iterations,
                completed,
                stopReason,
                toolSteps,
                usage.snapshot()
        );
    }

    /** 保存文本及其是否被截断。 */
    private record TruncatedText(String value, boolean truncated) {
    }

    /**
     * 根据公开运行状态生成可展示的进度摘要，不读取或转发模型隐藏思维链。
     */
    private static String publicThoughtSummary(
            int iteration,
            boolean toolsEnabled,
            List<AgentRunResult.ToolStep> toolSteps
    ) {
        if (iteration == 1) {
            return toolsEnabled ? "分析任务并规划下一步" : "分析问题并组织回答";
        }
        if (toolSteps.isEmpty()) {
            return "结合已有上下文调整下一步";
        }
        AgentRunResult.ToolStep previousStep = toolSteps.getLast();
        if (previousStep.success()) {
            return "已获得 " + previousStep.toolName() + " 的结果，正在判断下一步";
        }
        return previousStep.toolName() + " 执行失败，正在调整方案";
    }

    /** 标识完全相同的确定性工具失败，防止模型无限重复调用。 */
    private record ToolFailureSignature(String toolName, String arguments, String errorCode) {
    }

    /** 跨模型调用累计 Token 用量。 */
    private static final class UsageAccumulator {
        private long promptTokens;
        private long completionTokens;
        private long totalTokens;

        /** 累加单次模型响应的用量。 */
        void add(DeepSeekChatResponse.Usage usage) {
            if (usage == null) {
                return;
            }
            promptTokens += usage.promptTokens();
            completionTokens += usage.completionTokens();
            totalTokens += usage.totalTokens();
        }

        /** 返回当前累计值的不可变快照。 */
        AgentRunResult.Usage snapshot() {
            return new AgentRunResult.Usage(promptTokens, completionTokens, totalTokens);
        }
    }
}
