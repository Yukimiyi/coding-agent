package com.yukina.codingagent.agent;

/**
 * 观察 AgentLoop 的公开执行阶段，用于记录状态或实时推送事件。
 */
public interface AgentLoopObserver {

    /** 不处理任何事件的默认观察器。 */
    AgentLoopObserver NONE = new AgentLoopObserver() {
    };

    /**
     * 在新一轮模型调用开始前触发。
     *
     * @param iteration 一基模型调用轮次
     */
    default void onIterationStarted(int iteration) {
    }

    /**
     * 在新一轮分析开始时发布可安全展示的简短摘要。
     * 此摘要由程序生成，不包含模型的隐藏思维链。
     *
     * @param iteration 一基模型调用轮次
     * @param summary 程序根据公开状态生成的进度摘要
     */
    default void onProgress(int iteration, String summary) {
    }

    /**
     * 在收到最终回答的公开文本增量时触发。
     *
     * @param iteration 一基模型调用轮次
     * @param delta 新收到的公开回答文本
     */
    default void onAnswerDelta(int iteration, String delta) {
    }

    /**
     * 在当前响应转为工具调用时清除已展示的中间文本。
     *
     * @param iteration 一基模型调用轮次
     */
    default void onAnswerReset(int iteration) {
    }

    /**
     * 在模型响应到达后触发，仅暴露模型名称和工具调用数量。
     *
     * @param iteration 一基模型调用轮次
     * @param model 实际响应模型名称
     * @param toolCallCount 本轮工具调用数量
     */
    default void onModelResponse(int iteration, String model, int toolCallCount) {
    }

    /**
     * 在单个工具开始执行前触发。
     *
     * @param iteration 一基模型调用轮次
     * @param toolCallId 工具调用 ID
     * @param toolName 工具名称
     * @param arguments 已按轨迹上限截断的参数文本
     */
    default void onToolStarted(int iteration, String toolCallId, String toolName, String arguments) {
    }

    /**
     * 在单个工具执行完成后触发。
     *
     * @param toolStep 已包含结果或结构化错误的工具轨迹
     */
    default void onToolCompleted(AgentRunResult.ToolStep toolStep) {
    }
}
