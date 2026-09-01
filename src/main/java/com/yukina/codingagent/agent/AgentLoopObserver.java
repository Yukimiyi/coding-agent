package com.yukina.codingagent.agent;

import com.yukina.codingagent.agent.perception.ProjectSnapshot;
import com.yukina.codingagent.agent.plan.AgentPlan;
import com.yukina.codingagent.agent.reflection.ReflectionFeedback;

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
     * 在程序完成规划前项目感知后触发。
     *
     * @param snapshot 受限项目快照
     */
    default void onPerceptionCompleted(ProjectSnapshot snapshot) {
    }

    /** 在无工具 Planner 模型调用开始前触发。 */
    default void onPlanStarted() {
    }

    /**
     * 在初始结构化计划通过解析和规范化后触发。
     *
     * @param plan 首步骤已进入 IN_PROGRESS 的计划
     * @param fallbackUsed 是否使用确定性单步兜底计划
     * @param notice 可安全展示的规划结果说明
     */
    default void onPlanCreated(AgentPlan plan, boolean fallbackUsed, String notice) {
    }

    /**
     * 在 update_plan 申请通过程序校验后触发。
     *
     * @param plan 更新后的不可变计划
     * @param summary AI 提供的公开进度摘要
     */
    default void onPlanUpdated(AgentPlan plan, String summary) {
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
     * 在带工具调用的模型响应包含可公开普通文本时触发。
     * 该文本是模型主动输出的行动说明，不是 reasoning_content 或隐藏思维链。
     *
     * @param iteration 一基模型调用轮次
     * @param summary 已按轨迹上限截断的公开文本
     */
    default void onThought(int iteration, String summary) {
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
     * 在候选最终回答进入无工具反思审查前触发。
     *
     * @param iteration 产生候选回答的一基模型轮次
     */
    default void onReflectionStarted(int iteration) {
    }

    /**
     * 在反思审查得到结构化 PASS 或 REVISE 后触发。
     *
     * @param iteration 产生候选回答的一基模型轮次
     * @param feedback 可安全展示的结构化结论
     */
    default void onReflectionCompleted(int iteration, ReflectionFeedback feedback) {
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
