package com.yukina.codingagent.agent.run;

/**
 * 可通过 SSE 推送给客户端的 Agent 公开执行事件类型。
 */
public enum AgentRunEventType {
    QUEUED,
    RUNNING,
    ITERATION_STARTED,
    PROGRESS,
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
