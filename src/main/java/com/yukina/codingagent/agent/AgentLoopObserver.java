package com.yukina.codingagent.agent;

/**
 * 观察 AgentLoop 的公开执行阶段，用于记录状态或实时推送事件。
 */
public interface AgentLoopObserver {

    /** 不处理任何事件的默认观察器。 */
    AgentLoopObserver NONE = new AgentLoopObserver() {
    };

    /** 在新一轮模型调用开始前触发。 */
    default void onIterationStarted(int iteration) {
    }

    /** 在模型响应到达后触发，仅暴露模型名称和工具调用数量。 */
    default void onModelResponse(int iteration, String model, int toolCallCount) {
    }

    /** 在单个工具开始执行前触发。 */
    default void onToolStarted(int iteration, String toolCallId, String toolName, String arguments) {
    }

    /** 在单个工具执行完成后触发。 */
    default void onToolCompleted(AgentRunResult.ToolStep toolStep) {
    }
}
