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
import java.util.List;

/**
 * 驱动“模型推理 -> 工具执行 -> 结果回传”的 Agent 主循环。
 */
@Service
public class AgentLoop {

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
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("task must not be blank");
        }
        List<DeepSeekMessage> safeHistory = validateHistory(conversationHistory);

        List<DeepSeekMessage> messages = new ArrayList<>();
        messages.add(DeepSeekMessage.system(properties.systemPrompt()));
        messages.addAll(safeHistory);
        messages.add(DeepSeekMessage.user(task));
        List<AgentRunResult.ToolStep> toolSteps = new ArrayList<>();
        UsageAccumulator usage = new UsageAccumulator();
        String model = null;

        for (int iteration = 1; iteration <= properties.maxIterations(); iteration++) {
            DeepSeekChatResponse response = deepSeekClient.chat(
                    List.copyOf(messages),
                    toolRegistry.definitions()
            );
            model = response.model();
            usage.add(response.usage());
            DeepSeekMessage assistant = response.firstMessage();
            messages.add(assistant);

            List<DeepSeekToolCall> toolCalls = assistant.toolCalls() == null
                    ? List.of()
                    : assistant.toolCalls();
            if (toolCalls.isEmpty()) {
                String answer = assistant.content();
                boolean completed = answer != null && !answer.isBlank();
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

            List<ToolExecutionResult> executionResults = toolExecutor.executeAll(toolCalls);
            for (int index = 0; index < executionResults.size(); index++) {
                DeepSeekToolCall toolCall = toolCalls.get(index);
                ToolExecutionResult executionResult = executionResults.get(index);
                messages.add(executionResult.toToolMessage());
                toolSteps.add(toToolStep(iteration, toolCall, executionResult));
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
