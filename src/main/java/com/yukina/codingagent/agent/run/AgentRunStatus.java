package com.yukina.codingagent.agent.run;

/**
 * 异步 Agent 任务的生命周期状态。
 */
public enum AgentRunStatus {
    /** 任务已受理但后台线程尚未开始执行。 */
    QUEUED,
    /** AgentLoop 正在执行。 */
    RUNNING,
    /** 已成功产生最终回答。 */
    COMPLETED,
    /** 执行因未处理异常终止。 */
    FAILED,
    /** 用户取消请求已经生效。 */
    CANCELLED;

    /**
     * 判断当前状态是否已经终止。
     *
     * @return 完成、失败或取消状态返回 {@code true}
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
