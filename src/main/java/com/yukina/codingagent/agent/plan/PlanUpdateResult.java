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

    /**
     * 判断协调器是否接受了本次状态变更。
     *
     * @return 计划状态已被接受并更新时返回 {@code true}
     */
    public boolean success() {
        return executionResult != null && executionResult.success();
    }
}
