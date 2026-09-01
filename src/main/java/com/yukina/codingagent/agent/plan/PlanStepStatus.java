package com.yukina.codingagent.agent.plan;

/** 单个计划步骤的公开执行状态。 */
public enum PlanStepStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    BLOCKED
}
