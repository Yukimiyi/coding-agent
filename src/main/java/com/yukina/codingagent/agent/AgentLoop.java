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

@Service
public class AgentLoop {

    private final DeepSeekClient deepSeekClient;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final AgentLoopProperties properties;

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

    public AgentRunResult run(String task) {
        return run(task, List.of());
    }

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

    private TruncatedText truncate(String value) {
        if (value == null || value.length() <= properties.traceContentLimit()) {
            return new TruncatedText(value, false);
        }
        return new TruncatedText(value.substring(0, properties.traceContentLimit()), true);
    }

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

    private record TruncatedText(String value, boolean truncated) {
    }

    private static final class UsageAccumulator {
        private long promptTokens;
        private long completionTokens;
        private long totalTokens;

        void add(DeepSeekChatResponse.Usage usage) {
            if (usage == null) {
                return;
            }
            promptTokens += usage.promptTokens();
            completionTokens += usage.completionTokens();
            totalTokens += usage.totalTokens();
        }

        AgentRunResult.Usage snapshot() {
            return new AgentRunResult.Usage(promptTokens, completionTokens, totalTokens);
        }
    }
}
