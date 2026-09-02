package com.yukina.codingagent.agent.run;

/**
 * 可通过 SSE 推送给客户端的 Agent 公开执行事件类型。
 */
public enum AgentRunEventType {
    /** 任务已进入后台队列。 */
    QUEUED,
    /** 后台线程已开始执行任务。 */
    RUNNING,
    /** 项目感知阶段已生成有界快照。 */
    PERCEPTION_COMPLETED,
    /** 无工具规划模型调用已经开始。 */
    PLAN_STARTED,
    /** 初始计划已创建，可携带降级说明。 */
    PLAN_CREATED,
    /** 计划步骤状态或证据已经更新。 */
    PLAN_UPDATED,
    /** 新一轮 ReAct 模型调用已经开始。 */
    ITERATION_STARTED,
    /** Agent 公开进度摘要已经更新。 */
    PROGRESS,
    /** 可安全展示的思考摘要已经更新。 */
    THOUGHT,
    /** 最终回答新增了一段流式文本。 */
    ANSWER_DELTA,
    /** 前一候选回答需要清空并重新生成。 */
    ANSWER_RESET,
    /** 一次模型响应及工具调用数量已经确定。 */
    MODEL_RESPONSE,
    /** 独立 Reflection 审查已经开始。 */
    REFLECTION_STARTED,
    /** Reflection 已给出通过或修正结论。 */
    REFLECTION_COMPLETED,
    /** 一个工具调用即将执行。 */
    TOOL_STARTED,
    /** 一个工具调用已经完成并产生 Observation。 */
    TOOL_COMPLETED,
    /** Agent 运行已成功完成。 */
    COMPLETED,
    /** Agent 运行因异常失败。 */
    FAILED,
    /** Agent 运行已响应用户取消。 */
    CANCELLED
}
