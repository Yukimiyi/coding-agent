package com.yukina.codingagent.agent.run;

import com.yukina.codingagent.agent.AgentRunResult;

import java.time.Instant;

/**
 * 一条可重放的运行事件，不包含模型隐藏推理内容。
 */
public record AgentRunEvent(
        long sequence,
        String runId,
        Instant timestamp,
        AgentRunEventType type,
        AgentRunStatus status,
        Integer iteration,
        String model,
        Integer toolCallCount,
        String toolCallId,
        String toolName,
        String arguments,
        AgentRunResult.ToolStep toolStep,
        AgentRunResult result,
        String message
) {
}
