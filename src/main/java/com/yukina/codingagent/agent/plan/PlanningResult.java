package com.yukina.codingagent.agent.plan;

import com.yukina.codingagent.deepseek.DeepSeekChatResponse;

/**
 * Planner 生成的计划和对应模型用量。
 *
 * @param plan 结构化执行计划
 * @param usage 全部无工具规划调用的累计 Token 用量
 * @param fallbackUsed 是否在两次非法响应后使用确定性兜底计划
 * @param notice 可安全展示的规划结果说明
 */
public record PlanningResult(
        AgentPlan plan,
        DeepSeekChatResponse.Usage usage,
        boolean fallbackUsed,
        String notice
) {

    /** @throws IllegalArgumentException 计划为空时抛出 */
    public PlanningResult {
        if (plan == null) {
            throw new IllegalArgumentException("planning result must contain a plan");
        }
        notice = notice == null || notice.isBlank() ? "执行计划已创建" : notice;
    }
}
