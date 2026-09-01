package com.yukina.codingagent.agent.plan;

import com.yukina.codingagent.tool.ToolExecutionResult;

/**
 * 内部 update_plan 调用的审批结果。
 *
 * @param plan 接受时的新计划，拒绝时的原计划
 * @param executionResult 回传给模型的结构化 Observation
 * @param summary 接受时可公开展示的进度摘要
 */
public record PlanUpdateResult(
        AgentPlan plan,
        ToolExecutionResult executionResult,
        String summary
) {

    /** @return 计划状态是否已被接受并更新 */
    public boolean success() {
        return executionResult != null && executionResult.success();
    }
}
