package com.yukina.codingagent.agent;

import com.yukina.codingagent.deepseek.DeepSeekChatResponse;
import com.yukina.codingagent.deepseek.DeepSeekClient;
import com.yukina.codingagent.deepseek.DeepSeekMessage;
import com.yukina.codingagent.deepseek.DeepSeekToolCall;
import com.yukina.codingagent.tool.ToolExecutionResult;
import com.yukina.codingagent.tool.ToolExecutor;
import com.yukina.codingagent.tool.ToolRegistry;
import com.yukina.codingagent.tool.command.ExecutionEnvironmentProvider;
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
    private static final Set<String> MUTATING_TOOLS = Set.of("write_file", "edit_file", "delete_file");
    private static final String APPLY_CODE_CORRECTION =
            "This is a CODE conversation, but you returned an implementation without changing the project. "
                    + "Apply the requested code with the file tools, verify it when possible, and then summarize. "
                    + "Do not return the implementation only as a code block.";

    private final DeepSeekClient deepSeekClient;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final AgentLoopProperties properties;
    private final ExecutionEnvironmentProvider executionEnvironmentProvider;

    /**
     * 创建 Agent 循环服务。
     *
     * @param deepSeekClient DeepSeek 模型客户端
     * @param toolRegistry 可提供给模型的工具注册表
     * @param toolExecutor 工具执行器
     * @param properties 循环边界配置
     * @param executionEnvironmentProvider 当前宿主开发环境摘要提供者
     */
    public AgentLoop(
            DeepSeekClient deepSeekClient,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            AgentLoopProperties properties,
            ExecutionEnvironmentProvider executionEnvironmentProvider
    ) {
        this.deepSeekClient = deepSeekClient;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.properties = properties;
        this.executionEnvironmentProvider = executionEnvironmentProvider;
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

    /**
     * 执行不附带工作空间的纯对话，不向模型提供任何本地工具。
     *
     * @param task 当前用户问题
     * @param conversationHistory user 和 assistant 历史消息
     * @param observer 公开执行阶段观察器
     * @param cancellation 协作式取消信号
     * @return 不包含工具调用的 Agent 执行结果
     */
    public AgentRunResult runWithoutTools(
            String task,
            List<DeepSeekMessage> conversationHistory,
            AgentLoopObserver observer,
            AgentRunCancellation cancellation
    ) {
        return run(task, conversationHistory, observer, cancellation, false);
    }

    /**
     * 根据当前会话是否绑定工作空间执行统一的模型循环。
     *
     * @param task 当前用户任务
     * @param conversationHistory 已裁剪的对话历史
     * @param observer 可为空的公开事件观察器
     * @param cancellation 可为空的取消信号
     * @param toolsEnabled 是否向模型提供工具和执行环境信息
     * @return 最终回答、停止原因、轨迹和累计用量
     * @throws IllegalArgumentException 任务为空或历史角色不受支持时抛出
     */
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
        messages.add(DeepSeekMessage.system(systemPrompt(toolsEnabled)));
        messages.addAll(safeHistory);
        messages.add(DeepSeekMessage.user(task));
        List<AgentRunResult.ToolStep> toolSteps = new ArrayList<>();
        Set<ToolFailureSignature> failedToolCalls = new HashSet<>();
        UsageAccumulator usage = new UsageAccumulator();
        String model = null;
        boolean applyCodeCorrectionIssued = false;

        for (int iteration = 1; iteration <= properties.maxIterations(); iteration++) {
            safeCancellation.throwIfCancellationRequested();
            safeObserver.onIterationStarted(iteration);
            safeObserver.onProgress(iteration, publicProgressSummary(iteration, toolsEnabled, toolSteps));
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
                if (toolsEnabled
                        && !applyCodeCorrectionIssued
                        && containsCodeBlock(answer)
                        && !hasSuccessfulMutation(toolSteps)) {
                    applyCodeCorrectionIssued = true;
                    safeObserver.onAnswerReset(iteration);
                    messages.add(DeepSeekMessage.user(APPLY_CODE_CORRECTION));
                    continue;
                }
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
     *
     * @param iteration 工具发生的模型轮次
     * @param toolCall 原始模型工具调用
     * @param executionResult 归一化工具执行结果
     * @return 参数和内容均按上限截断的工具轨迹
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
     *
     * @param value 原始参数或结果文本
     * @return 文本值和截断标记
     */
    private TruncatedText truncate(String value) {
        if (value == null || value.length() <= properties.traceContentLimit()) {
            return new TruncatedText(value, false);
        }
        return new TruncatedText(value.substring(0, properties.traceContentLimit()), true);
    }

    /**
     * 统一构造不可变的 Agent 执行结果。
     *
     * @param answer 最终或停止时已有回答
     * @param model 实际响应模型
     * @param iterations 已执行轮数
     * @param completed 是否正常获得最终回答
     * @param stopReason 停止原因
     * @param toolSteps 工具轨迹
     * @param usage 累计 Token 计数器
     * @return 不可变 Agent 执行结果
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

    /**
     * 保存文本及其是否被截断。
     *
     * @param value 实际保留文本
     * @param truncated 是否丢弃了后续文本
     */
    private record TruncatedText(String value, boolean truncated) {
    }

    /**
     * 在工具会话中附加真实环境能力，避免模型反复调用不存在的编译器。
     *
     * @param toolsEnabled 是否启用工作空间工具
     * @return 纯聊天或带环境摘要的系统提示词
     */
    private String systemPrompt(boolean toolsEnabled) {
        if (!toolsEnabled) {
            return CHAT_ONLY_SYSTEM_PROMPT;
        }
        return properties.systemPrompt() + "\n\n" + executionEnvironmentProvider.agentSummary();
    }

    /**
     * 根据公开运行状态生成可展示的进度摘要，不读取或转发模型隐藏思维链。
     *
     * @param iteration 当前一基轮次
     * @param toolsEnabled 是否启用工具
     * @param toolSteps 已完成工具轨迹
     * @return 面向用户的简短进度摘要
     */
    private static String publicProgressSummary(
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

    /**
     * 判断最终文本是否包含应当落盘的围栏代码块。
     *
     * @param answer 模型候选最终回答
     * @return 包含 Markdown 围栏代码块时返回 {@code true}
     */
    private static boolean containsCodeBlock(String answer) {
        return answer != null && answer.contains("```");
    }

    /**
     * 判断本轮是否已经通过文件工具产生过成功变更。
     *
     * @param toolSteps 已完成工具轨迹
     * @return 至少一个写、改或删工具成功时返回 {@code true}
     */
    private static boolean hasSuccessfulMutation(List<AgentRunResult.ToolStep> toolSteps) {
        return toolSteps.stream().anyMatch(step -> step.success() && MUTATING_TOOLS.contains(step.toolName()));
    }

    /**
     * 标识完全相同的确定性工具失败，防止模型无限重复调用。
     *
     * @param toolName 工具名称
     * @param arguments 已截断参数
     * @param errorCode 稳定错误码
     */
    private record ToolFailureSignature(String toolName, String arguments, String errorCode) {
    }

    /** 跨模型调用累计 Token 用量。 */
    private static final class UsageAccumulator {
        private long promptTokens;
        private long completionTokens;
        private long totalTokens;

        /**
         * 累加单次模型响应的用量。
         *
         * @param usage 单次响应 Token 用量；为 {@code null} 时忽略
         */
        void add(DeepSeekChatResponse.Usage usage) {
            if (usage == null) {
                return;
            }
            promptTokens += usage.promptTokens();
            completionTokens += usage.completionTokens();
            totalTokens += usage.totalTokens();
        }

        /** @return 当前累计 Token 用量的不可变快照 */
        AgentRunResult.Usage snapshot() {
            return new AgentRunResult.Usage(promptTokens, completionTokens, totalTokens);
        }
    }
}
