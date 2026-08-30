package com.yukina.codingagent.agent.run;

import com.yukina.codingagent.agent.AgentRunResult;

import java.time.Instant;
import java.util.List;

/** 持久化的终态 Agent 运行摘要，用于重启后恢复工具轨迹。 */
public record AgentRunHistory(
        String runId,
        String requestId,
        String conversationId,
        String workspaceId,
        AgentRunStatus status,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        List<AgentRunResult.ToolStep> toolSteps,
        AgentRunResult result,
        String error
) {
    /** 对工具轨迹创建不可变快照。 */
    public AgentRunHistory {
        toolSteps = toolSteps == null ? List.of() : List.copyOf(toolSteps);
    }
}
