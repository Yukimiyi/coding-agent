package com.yukina.codingagent.agent;

import com.yukina.codingagent.tool.ToolExecutionResult;

import java.util.List;

public record AgentRunResult(
        String answer,
        String model,
        int iterations,
        boolean completed,
        StopReason stopReason,
        List<ToolStep> toolSteps,
        Usage usage
) {

    public AgentRunResult {
        toolSteps = toolSteps == null ? List.of() : List.copyOf(toolSteps);
    }

    public enum StopReason {
        COMPLETED,
        EMPTY_RESPONSE,
        MAX_ITERATIONS,
        TOOL_CALL_LIMIT,
        INVALID_TOOL_CALL
    }

    public record ToolStep(
            int iteration,
            String toolCallId,
            String toolName,
            String arguments,
            boolean argumentsTruncated,
            boolean success,
            String content,
            boolean contentTruncated,
            ToolExecutionResult.Error error
    ) {
    }

    public record Usage(long promptTokens, long completionTokens, long totalTokens) {
    }
}
