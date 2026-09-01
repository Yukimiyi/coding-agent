package com.yukina.codingagent.agent.reflection;

import com.yukina.codingagent.agent.AgentRunResult;
import com.yukina.codingagent.agent.plan.AgentPlan;

import java.util.List;

/**
 * 对候选最终回答及其公开执行证据进行一次无工具审查。
 */
public interface ReflectionReviewer {

    /**
     * 判断当前实现可以结束，还是应把具体问题送回 ReAct 循环。
     *
     * @param task 原始用户任务
     * @param candidateAnswer 模型准备返回的候选最终回答
     * @param toolSteps 本次运行已产生的受限工具轨迹
     * @param plan 可选 Plan-and-Solve 最终计划状态
     * @return PASS 或 REVISE 结论及本次模型用量
     */
    ReflectionReview review(
            String task,
            String candidateAnswer,
            List<AgentRunResult.ToolStep> toolSteps,
            AgentPlan plan
    );
}
