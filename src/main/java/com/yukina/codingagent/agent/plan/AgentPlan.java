package com.yukina.codingagent.agent.plan;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 单次 CODE 任务的公开实施计划和验收条件。
 *
 * @param goal 当前任务的简短目标
 * @param steps 有序执行步骤
 * @param acceptanceCriteria 整体完成条件
 */
public record AgentPlan(
        String goal,
        List<PlanStep> steps,
        List<String> acceptanceCriteria
) {

    /** 校验目标、步骤 ID 唯一性并复制集合。 */
    public AgentPlan {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("plan goal must not be blank");
        }
        steps = steps == null ? List.of() : List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("plan must contain at least one step");
        }
        Set<String> ids = new HashSet<>();
        if (steps.stream().anyMatch(step -> step == null || !ids.add(step.id()))) {
            throw new IllegalArgumentException("plan step ids must be unique");
        }
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
    }

    /** @return 所有步骤均有真实证据并处于 COMPLETED 时返回 {@code true} */
    public boolean allCompleted() {
        return steps.stream().allMatch(step -> step.status() == PlanStepStatus.COMPLETED);
    }

    /** @return 至少一个步骤处于 BLOCKED 时返回 {@code true} */
    public boolean hasBlockedStep() {
        return steps.stream().anyMatch(step -> step.status() == PlanStepStatus.BLOCKED);
    }

    /** @return 仍有可继续处理的 PENDING 或 IN_PROGRESS 步骤时返回 {@code true} */
    public boolean hasRunnableStep() {
        return steps.stream().anyMatch(step -> step.status() == PlanStepStatus.PENDING
                || step.status() == PlanStepStatus.IN_PROGRESS);
    }

    /**
     * 生成提供给 ReAct 模型的公开计划，不包含隐藏推理内容。
     *
     * @return 带步骤状态和验收条件的纯文本计划
     */
    public String toPrompt() {
        StringBuilder prompt = new StringBuilder("Goal: ").append(goal).append("\nSteps:");
        for (PlanStep step : steps) {
            prompt.append("\n- [").append(step.status()).append("] ")
                    .append(step.id()).append(": ").append(step.description())
                    .append(" | evidence: ").append(step.evidenceType())
                    .append(" | verification: ").append(step.verification());
        }
        if (!acceptanceCriteria.isEmpty()) {
            prompt.append("\nAcceptance criteria:");
            acceptanceCriteria.forEach(item -> prompt.append("\n- ").append(item));
        }
        return prompt.toString();
    }
}
