package com.yukina.codingagent.agent.run;

/**
 * 异步 Agent 任务的生命周期状态。
 */
public enum AgentRunStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    /**
     * 判断当前状态是否已经终止。
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
