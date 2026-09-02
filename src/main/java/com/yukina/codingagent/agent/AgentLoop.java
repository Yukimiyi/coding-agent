package com.yukina.codingagent.agent;

import com.yukina.codingagent.agent.perception.ProjectSnapshot;
import com.yukina.codingagent.agent.perception.ProjectSnapshotProvider;
import com.yukina.codingagent.agent.plan.AgentPlan;
import com.yukina.codingagent.agent.plan.PlanCoordinator;
import com.yukina.codingagent.agent.plan.PlanUpdateResult;
import com.yukina.codingagent.agent.plan.PlanningProperties;
import com.yukina.codingagent.agent.plan.PlanningResult;
import com.yukina.codingagent.agent.plan.PlanningService;
import com.yukina.codingagent.agent.reflection.ReflectionFeedback;
import com.yukina.codingagent.agent.reflection.ReflectionProperties;
import com.yukina.codingagent.agent.reflection.ReflectionReview;
import com.yukina.codingagent.agent.reflection.ReflectionReviewer;
import com.yukina.codingagent.deepseek.DeepSeekChatResponse;
import com.yukina.codingagent.deepseek.DeepSeekClient;
import com.yukina.codingagent.deepseek.DeepSeekMessage;
import com.yukina.codingagent.deepseek.DeepSeekToolCall;
import com.yukina.codingagent.deepseek.DeepSeekToolDefinition;
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

    /** CHAT 会话使用的无工作空间系统提示词。 */
    private static final String CHAT_ONLY_SYSTEM_PROMPT =
            "You are a coding assistant in a conversation without an attached workspace. "
                    + "Answer from the conversation context. You cannot inspect, modify, or execute local files, "
                    + "so do not claim that you performed those actions.";
    /** 能够改变工作区文件状态的工具名称集合。 */
    private static final Set<String> MUTATING_TOOLS = Set.of("write_file", "edit_file", "delete_file");

    /** 负责发送普通及流式模型请求的 DeepSeek 客户端。 */
    private final DeepSeekClient deepSeekClient;
    /** 向模型公开当前可用工具定义的注册表。 */
    private final ToolRegistry toolRegistry;
    /** 校验并执行模型工具调用的统一执行器。 */
    private final ToolExecutor toolExecutor;
    /** Agent 迭代次数、单轮工具数和轨迹长度边界。 */
    private final AgentLoopProperties properties;
    /** 为系统提示词提供不含敏感路径的宿主环境能力摘要。 */
    private final ExecutionEnvironmentProvider executionEnvironmentProvider;
    /** 在候选答案结束前执行独立审查的 Reflection 服务。 */
    private final ReflectionReviewer reflectionReviewer;
    /** Reflection 轮数和输入字符预算。 */
    private final ReflectionProperties reflectionProperties;
    /** 在规划前采集有界项目结构和描述文件的感知服务。 */
    private final ProjectSnapshotProvider projectSnapshotProvider;
    /** 根据任务、历史和项目快照生成初始实施计划。 */
    private final PlanningService planningService;
    /** Planning 开关、步骤数量及项目快照边界。 */
    private final PlanningProperties planningProperties;
    /** 审批 update_plan 请求并绑定真实工具证据的计划状态机。 */
    private final PlanCoordinator planCoordinator;

    /**
     * 创建 Agent 循环服务。
     *
     * @param deepSeekClient DeepSeek 模型客户端
     * @param toolRegistry 可提供给模型的工具注册表
     * @param toolExecutor 工具执行器
     * @param properties 循环边界配置
     * @param executionEnvironmentProvider 当前宿主开发环境摘要提供者
     * @param reflectionReviewer 候选最终回答的无工具审查器
     * @param reflectionProperties 反思次数和上下文边界
     * @param projectSnapshotProvider 规划前项目感知提供者
     * @param planningService 无工具计划生成器
     * @param planningProperties 规划与快照边界
     * @param planCoordinator update_plan 状态机和证据审批器
     */
    public AgentLoop(
            DeepSeekClient deepSeekClient,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            AgentLoopProperties properties,
            ExecutionEnvironmentProvider executionEnvironmentProvider,
            ReflectionReviewer reflectionReviewer,
            ReflectionProperties reflectionProperties,
            ProjectSnapshotProvider projectSnapshotProvider,
            PlanningService planningService,
            PlanningProperties planningProperties,
            PlanCoordinator planCoordinator
    ) {
        this.deepSeekClient = deepSeekClient;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.properties = properties;
        this.executionEnvironmentProvider = executionEnvironmentProvider;
        this.reflectionReviewer = reflectionReviewer;
        this.reflectionProperties = reflectionProperties;
        this.projectSnapshotProvider = projectSnapshotProvider;
        this.planningService = planningService;
        this.planningProperties = planningProperties;
        this.planCoordinator = planCoordinator;
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
     * @param conversationHistory 可包含前置滚动摘要 system 消息及最近 user/assistant 轮次
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
     * @param conversationHistory 可包含前置滚动摘要的历史消息
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

        UsageAccumulator usage = new UsageAccumulator();
        AgentPlan plan = null;
        if (toolsEnabled && planningProperties.enabled()) {
            safeCancellation.throwIfCancellationRequested();
            ProjectSnapshot snapshot = projectSnapshotProvider.capture();
            safeObserver.onPerceptionCompleted(snapshot);
            safeObserver.onPlanStarted();
            PlanningResult planningResult = planningService.createPlan(task, safeHistory, snapshot);
            safeCancellation.throwIfCancellationRequested();
            plan = planningResult.plan();
            usage.add(planningResult.usage());
            safeObserver.onPlanCreated(
                    plan,
                    planningResult.fallbackUsed(),
                    planningResult.notice()
            );
        }

        List<DeepSeekMessage> messages = new ArrayList<>();
        messages.add(DeepSeekMessage.system(systemPrompt(toolsEnabled, plan, task)));
        messages.addAll(safeHistory);
        messages.add(DeepSeekMessage.user(task));
        List<AgentRunResult.ToolStep> toolSteps = new ArrayList<>();
        Set<ToolFailureSignature> failedToolCalls = new HashSet<>();
        List<DeepSeekToolDefinition> availableTools = availableTools(toolsEnabled, plan != null);
        String model = null;
        boolean applyCodeCorrectionIssued = false;
        boolean planIncompleteCorrectionIssued = false;
        boolean languageCorrectionIssued = false;
        int reflectionRounds = 0;
        int reflectionRevisions = 0;

        for (int iteration = 1; iteration <= properties.maxIterations(); iteration++) {
            safeCancellation.throwIfCancellationRequested();
            safeObserver.onIterationStarted(iteration);
            safeObserver.onProgress(iteration, publicProgressSummary(iteration, toolsEnabled, toolSteps));
            // The model may update the plan only from observations that were visible when this turn began.
            List<AgentRunResult.ToolStep> observedToolSteps = List.copyOf(toolSteps);
            messages.set(0, DeepSeekMessage.system(systemPrompt(toolsEnabled, plan, task)));
            DeepSeekChatResponse response;
            try {
                int currentIteration = iteration;
                response = deepSeekClient.chatStream(
                        List.copyOf(messages),
                        availableTools,
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
                        usage,
                        plan,
                        reflectionRounds,
                        reflectionRevisions
                );
            }
            if (!toolCalls.isEmpty() && assistant.content() != null && !assistant.content().isBlank()) {
                safeObserver.onAnswerReset(iteration);
                if (toolsEnabled) {
                    String publicThought = ResponseLanguagePolicy.requiresChineseRewrite(task, assistant.content())
                            ? publicProgressSummary(iteration, true, toolSteps)
                            : truncate(assistant.content()).value();
                    safeObserver.onThought(iteration, publicThought);
                }
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
                        usage,
                        plan,
                        reflectionRounds,
                        reflectionRevisions
                );
            }
            if (toolCalls.isEmpty()) {
                String answer = assistant.content();
                if (!languageCorrectionIssued
                        && ResponseLanguagePolicy.requiresChineseRewrite(task, answer)) {
                    languageCorrectionIssued = true;
                    safeObserver.onAnswerReset(iteration);
                    messages.add(DeepSeekMessage.user(
                            "请保留事实、代码、命令和路径不变，将面向用户的说明改写为简体中文。"
                                    + "只需继续完成当前任务，不要解释这条语言纠正要求。"
                    ));
                    continue;
                }
                if (toolsEnabled
                        && !applyCodeCorrectionIssued
                        && containsCodeBlock(answer)
                        && !hasSuccessfulMutation(toolSteps)) {
                    applyCodeCorrectionIssued = true;
                    safeObserver.onAnswerReset(iteration);
                    messages.add(DeepSeekMessage.user(applyCodeCorrection(task)));
                    continue;
                }
                boolean completed = answer != null && !answer.isBlank();
                if (plan != null && completed && (finishReason == null || "stop".equals(finishReason))) {
                    if (plan.hasRunnableStep()) {
                        if (!planIncompleteCorrectionIssued) {
                            planIncompleteCorrectionIssued = true;
                            safeObserver.onAnswerReset(iteration);
                            messages.add(DeepSeekMessage.user(
                                    planIncompleteCorrection(task) + "\n\nCurrent plan:\n" + plan.toPrompt()
                            ));
                            continue;
                        }
                        return result(
                                answer,
                                model,
                                iteration,
                                false,
                                AgentRunResult.StopReason.PLAN_INCOMPLETE,
                                toolSteps,
                                usage,
                                plan,
                                reflectionRounds,
                                reflectionRevisions
                        );
                    }
                    if (plan.hasBlockedStep()) {
                        return result(
                                answer,
                                model,
                                iteration,
                                false,
                                AgentRunResult.StopReason.PLAN_BLOCKED,
                                toolSteps,
                                usage,
                                plan,
                                reflectionRounds,
                                reflectionRevisions
                        );
                    }
                }
                if (shouldReflect(
                        toolsEnabled,
                        completed,
                        finishReason,
                        iteration,
                        reflectionRounds,
                        toolSteps
                )) {
                    safeCancellation.throwIfCancellationRequested();
                    safeObserver.onReflectionStarted(iteration);
                    ReflectionReview review = reflectionReviewer.review(
                            task,
                            answer,
                            List.copyOf(toolSteps),
                            plan
                    );
                    safeCancellation.throwIfCancellationRequested();
                    reflectionRounds++;
                    usage.add(review.usage());
                    ReflectionFeedback feedback = review.feedback();
                    safeObserver.onReflectionCompleted(iteration, feedback);
                    if (feedback.requiresRevision()) {
                        reflectionRevisions++;
                        safeObserver.onAnswerReset(iteration);
                        if (plan != null) {
                            plan = planCoordinator.reopenLastStepForRevision(plan, toolSteps.size());
                            safeObserver.onPlanUpdated(plan, "反思发现问题，重新打开最终步骤");
                        }
                        String revisionInstruction = feedback.revisionInstruction(
                                ResponseLanguagePolicy.prefersChinese(task)
                        );
                        if (plan != null) {
                            revisionInstruction += "\n\nUpdated plan after review:\n" + plan.toPrompt();
                        }
                        messages.add(DeepSeekMessage.user(revisionInstruction));
                        continue;
                    }
                }
                if (completed && finishReason != null && !"stop".equals(finishReason)) {
                    return result(
                            answer,
                            model,
                            iteration,
                            false,
                            AgentRunResult.StopReason.MODEL_STOPPED,
                            toolSteps,
                            usage,
                            plan,
                            reflectionRounds,
                            reflectionRevisions
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
                        usage,
                        plan,
                        reflectionRounds,
                        reflectionRevisions
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
                        usage,
                        plan,
                        reflectionRounds,
                        reflectionRevisions
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
                        usage,
                        plan,
                        reflectionRounds,
                        reflectionRevisions
                );
            }

            for (DeepSeekToolCall toolCall : toolCalls) {
                safeCancellation.throwIfCancellationRequested();
                String toolName = toolCall.function() == null ? null : toolCall.function().name();
                String arguments = toolCall.function() == null ? null : toolCall.function().arguments();
                safeObserver.onToolStarted(iteration, toolCall.id(), toolName, truncate(arguments).value());
                PlanUpdateResult planUpdate = null;
                ToolExecutionResult executionResult;
                if (plan != null && PlanCoordinator.TOOL_NAME.equals(toolName)) {
                    planUpdate = planCoordinator.update(toolCall, plan, observedToolSteps);
                    plan = planUpdate.plan();
                    executionResult = planUpdate.executionResult();
                } else {
                    executionResult = toolExecutor.execute(toolCall);
                }
                safeCancellation.throwIfCancellationRequested();
                messages.add(executionResult.toToolMessage());
                AgentRunResult.ToolStep toolStep = toToolStep(iteration, toolCall, executionResult);
                toolSteps.add(toolStep);
                safeObserver.onToolCompleted(toolStep);
                if (planUpdate != null && planUpdate.success()) {
                    safeObserver.onPlanUpdated(plan, planUpdate.summary());
                }
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
                                usage,
                                plan,
                                reflectionRounds,
                                reflectionRevisions
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
                usage,
                plan,
                reflectionRounds,
                reflectionRevisions
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
        boolean conversationStarted = false;
        for (DeepSeekMessage message : safeHistory) {
            if (message == null) {
                throw new IllegalArgumentException("conversation history must not contain null messages");
            }
            if ("system".equals(message.role())) {
                if (conversationStarted) {
                    throw new IllegalArgumentException("conversation memory system messages must precede conversation turns");
                }
            } else if ("user".equals(message.role()) || "assistant".equals(message.role())) {
                conversationStarted = true;
            } else {
                throw new IllegalArgumentException(
                        "conversation history may only contain system memory, user, and assistant messages"
                );
            }
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
     * @param plan 当前最终公开计划
     * @param reflectionRounds 已执行反思轮数
     * @param reflectionRevisions Reflection 要求修正的次数
     * @return 不可变 Agent 执行结果
     */
    private static AgentRunResult result(
            String answer,
            String model,
            int iterations,
            boolean completed,
            AgentRunResult.StopReason stopReason,
            List<AgentRunResult.ToolStep> toolSteps,
            UsageAccumulator usage,
            AgentPlan plan,
            int reflectionRounds,
            int reflectionRevisions
    ) {
        return new AgentRunResult(
                answer,
                model,
                iterations,
                completed,
                stopReason,
                toolSteps,
                usage.snapshot(),
                plan,
                new AgentRunResult.ReflectionTrace(reflectionRounds, reflectionRevisions)
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
     * @param plan 可选公开实施计划
     * @param task 当前用户任务，用于确定回复语言
     * @return 纯聊天或带环境摘要的系统提示词
     */
    private String systemPrompt(boolean toolsEnabled, AgentPlan plan, String task) {
        if (!toolsEnabled) {
            return CHAT_ONLY_SYSTEM_PROMPT + "\n\n" + ResponseLanguagePolicy.instructionFor(task);
        }
        StringBuilder prompt = new StringBuilder(properties.systemPrompt())
                .append("\n\n").append(ResponseLanguagePolicy.instructionFor(task))
                .append("\n\n").append(executionEnvironmentProvider.agentSummary());
        if (plan != null) {
            prompt.append("\n\nPUBLIC EXECUTION PLAN\n")
                    .append(plan.toPrompt())
                    .append("\n\nFollow this plan as a high-level guide while using ReAct to adapt actions to observations. ")
                    .append("Update the plan only from tool observations already returned in an earlier model turn. ")
                    .append("Tool calls emitted alongside update_plan are new actions and cannot prove that update. ")
                    .append("When a prior observation changes a step status, call update_plan before emitting new action ")
                    .append("tools for the next step. Send every plan step in that update. ")
                    .append("Do not call update_plan at the start merely to announce the existing statuses; execute the ")
                    .append("current IN_PROGRESS step first and submit it only when a status or evidence changes. ")
                    .append("If one tool batch already completed multiple adjacent steps, report all of those real status ")
                    .append("changes together; the coordinator will bind separate matching evidence to each step. ")
                    .append("Omit evidenceToolCallIds unless you need to provide exact protocol call-id hints. The ")
                    .append("coordinator automatically binds authoritative evidence from the real tool trace. ")
                    .append("A failed tool call normally leaves the current step IN_PROGRESS. Use BLOCKED only when ")
                    .append("a supported external blocker is proven by failed tool evidence. Before returning a final ")
                    .append("answer, all steps must be COMPLETED or a genuine blocker must be recorded.");
        }
        return prompt.toString();
    }

    /**
     * 构建要求模型真正修改工作区的内部纠正指令。
     *
     * @param task 当前用户任务，用于选择指令语言
     * @return 与任务语言一致的代码落盘纠正指令
     */
    private static String applyCodeCorrection(String task) {
        if (ResponseLanguagePolicy.prefersChinese(task)) {
            return "这是编写项目会话，但你只返回了实现文本，没有修改项目。请使用文件工具完成代码修改，"
                    + "在条件允许时进行验证，最后用中文简要总结。不要只返回代码块。";
        }
        return "This is a CODE conversation, but you returned an implementation without changing the project. "
                + "Apply the requested code with the file tools, verify it when possible, and then summarize. "
                + "Do not return the implementation only as a code block.";
    }

    /**
     * 构建要求模型继续完成未结束计划步骤的内部纠正指令。
     *
     * @param task 当前用户任务，用于选择指令语言
     * @return 与任务语言一致的计划未完成纠正指令
     */
    private static String planIncompleteCorrection(String task) {
        if (ResponseLanguagePolicy.prefersChinese(task)) {
            return "你在公开任务计划仍有可执行步骤时尝试结束。请继续 ReAct 循环，获取真实工具证据，"
                    + "并使用 update_plan 准确更新所有步骤状态。没有成功的非计划工具证据时，不得声称步骤已完成。";
        }
        return "You attempted to finish while the public execution plan still has runnable steps. Continue the ReAct "
                + "process, obtain real tool evidence, and use update_plan to keep every step status accurate. "
                + "Do not claim a step is complete without successful non-plan tool evidence.";
    }

    /**
     * 合并工作区工具与当前运行专用的 update_plan 定义。
     *
     * @param toolsEnabled 是否为 CODE 会话
     * @param planEnabled 当前运行是否生成了计划
     * @return 发送给模型的不可变工具定义列表
     */
    private List<DeepSeekToolDefinition> availableTools(boolean toolsEnabled, boolean planEnabled) {
        if (!toolsEnabled) {
            return List.of();
        }
        if (!planEnabled) {
            return toolRegistry.definitions();
        }
        List<DeepSeekToolDefinition> definitions = new ArrayList<>(toolRegistry.definitions());
        definitions.add(planCoordinator.definition());
        return List.copyOf(definitions);
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
     * 判断候选最终回答是否需要执行一次结束前反思。
     * 为 REVISE 预留“修正行动 + 新最终回答”两轮，避免在循环边界制造无法完成的修改。
     *
     * @param toolsEnabled 当前是否为 CODE 会话
     * @param completed 候选回答是否非空
     * @param finishReason 模型停止原因
     * @param iteration 当前一基轮次
     * @param reflectionRounds 已执行反思次数
     * @param toolSteps 当前工具轨迹
     * @return 当前轮满足反思触发条件且未达到配置上限时返回 {@code true}
     */
    private boolean shouldReflect(
            boolean toolsEnabled,
            boolean completed,
            String finishReason,
            int iteration,
            int reflectionRounds,
            List<AgentRunResult.ToolStep> toolSteps
    ) {
        return toolsEnabled
                && completed
                && (finishReason == null || "stop".equals(finishReason))
                && reflectionRounds < reflectionProperties.maxRounds()
                && iteration + 2 <= properties.maxIterations()
                && hasSuccessfulMutation(toolSteps);
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
        /** 已累计的输入 Token 数。 */
        private long promptTokens;
        /** 已累计的输出 Token 数。 */
        private long completionTokens;
        /** 已累计的输入与输出 Token 总数。 */
        private long totalTokens;

        /** 创建所有计数均为零的用量累加器。 */
        private UsageAccumulator() {
        }

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

        /**
         * 生成当前累计用量快照。
         *
         * @return 当前累计 Token 用量的不可变快照
         */
        AgentRunResult.Usage snapshot() {
            return new AgentRunResult.Usage(promptTokens, completionTokens, totalTokens);
        }
    }
}
