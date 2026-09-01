package com.yukina.codingagent.agent.run;

/**
 * 可通过 SSE 推送给客户端的 Agent 公开执行事件类型。
 */
public enum AgentRunEventType {
    QUEUED,
    RUNNING,
    PERCEPTION_COMPLETED,
    PLAN_STARTED,
    PLAN_CREATED,
    PLAN_UPDATED,
    ITERATION_STARTED,
    PROGRESS,
    THOUGHT,
    ANSWER_DELTA,
    ANSWER_RESET,
    MODEL_RESPONSE,
    REFLECTION_STARTED,
    REFLECTION_COMPLETED,
    TOOL_STARTED,
    TOOL_COMPLETED,
    COMPLETED,
    FAILED,
    CANCELLED
}
