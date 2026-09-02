package com.yukina.codingagent.agent.plan;

/** 单个计划步骤的公开执行状态。 */
public enum PlanStepStatus {
    /** 尚未开始，且没有可使用的工具证据窗口。 */
    PENDING,
    /** 当前正在执行；同一计划最多允许一个步骤处于该状态。 */
    IN_PROGRESS,
    /** 已由进入执行状态之后产生的成功工具证据证明完成。 */
    COMPLETED,
    /** 已由不可绕过的外部阻塞证据证明无法继续。 */
    BLOCKED
}
