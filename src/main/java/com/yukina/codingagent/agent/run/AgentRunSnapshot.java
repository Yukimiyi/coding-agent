package com.yukina.codingagent.agent.run;

import com.yukina.codingagent.agent.AgentRunResult;

import java.time.Instant;
import java.util.List;

/**
 * 供状态查询与页面恢复使用的异步任务快照。
 */
public record AgentRunSnapshot(
        String runId,
        String requestId,
        String conversationId,
        String workspaceId,
        boolean conversationCreated,
        AgentRunStatus status,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        int currentIteration,
        List<AgentRunResult.ToolStep> toolSteps,
        AgentRunResult result,
        String error,
        long lastSequence
) {
    /** 对工具轨迹创建不可变副本。 */
    public AgentRunSnapshot {
        toolSteps = toolSteps == null ? List.of() : List.copyOf(toolSteps);
    }
}
