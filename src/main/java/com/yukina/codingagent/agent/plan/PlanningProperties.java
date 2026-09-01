package com.yukina.codingagent.agent.plan;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Plan-and-Solve 的模型与项目快照边界。
 *
 * @param enabled CODE 会话是否启用规划
 * @param maxSteps 单个计划最多步骤数
 * @param maxContextChars Planner 输入最大字符数
 * @param snapshotDepth 项目结构感知深度
 * @param maxSnapshotFiles 项目快照最大条目数
 * @param maxDescriptorChars 构建描述文件总字符数
 * @param systemPrompt Planner 的结构化输出提示词
 */
@ConfigurationProperties(prefix = "agent.planning")
public record PlanningProperties(
        boolean enabled,
        int maxSteps,
        int maxContextChars,
        int snapshotDepth,
        int maxSnapshotFiles,
        int maxDescriptorChars,
        String systemPrompt
) {

    /** 校验所有规划和感知上限。 */
    public PlanningProperties {
        if (maxSteps <= 0 || maxContextChars <= 0 || snapshotDepth <= 0
                || maxSnapshotFiles <= 0 || maxDescriptorChars <= 0) {
            throw new IllegalArgumentException("agent.planning limits must be positive");
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("agent.planning.system-prompt must not be blank");
        }
    }
}
