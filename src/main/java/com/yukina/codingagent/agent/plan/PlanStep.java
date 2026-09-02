package com.yukina.codingagent.agent.plan;

import java.util.List;

/**
 * 一个可执行、可验证的公开计划步骤。
 *
 * @param id 运行内稳定步骤 ID
 * @param description 要完成的工作
 * @param verification 可观察的验收条件
 * @param evidenceType 完成步骤所需的工具证据类型
 * @param status 当前状态
 * @param evidenceFromToolStep 进入本轮执行状态时的工具轨迹起始下标
 * @param evidenceToolCallIds 支撑完成或阻塞状态的工具调用 ID
 * @param blocker BLOCKED 状态的结构化原因
 */
public record PlanStep(
        String id,
        String description,
        String verification,
        PlanEvidenceType evidenceType,
        PlanStepStatus status,
        int evidenceFromToolStep,
        List<String> evidenceToolCallIds,
        PlanBlocker blocker
) {

    /** 校验必填字段并复制证据列表。 */
    public PlanStep {
        if (id == null || id.isBlank() || description == null || description.isBlank()
                || verification == null || verification.isBlank() || evidenceType == null || status == null) {
            throw new IllegalArgumentException("plan step fields must not be blank");
        }
        if (status == PlanStepStatus.PENDING && evidenceFromToolStep != -1) {
            throw new IllegalArgumentException("pending plan step must not have an evidence window");
        }
        if (status != PlanStepStatus.PENDING && evidenceFromToolStep < 0) {
            throw new IllegalArgumentException("active plan step must have a non-negative evidence window");
        }
        evidenceToolCallIds = evidenceToolCallIds == null ? List.of() : List.copyOf(evidenceToolCallIds);
        if (status == PlanStepStatus.BLOCKED && blocker == null) {
            throw new IllegalArgumentException("blocked plan step must contain blocker details");
        }
        if (status != PlanStepStatus.BLOCKED) {
            blocker = null;
        }
    }

    /**
     * 使用默认 GENERAL 证据类型创建兼容步骤。
     *
     * @param id 步骤 ID
     * @param description 工作描述
     * @param verification 验收条件
     * @param status 初始状态
     * @param evidenceToolCallIds 已绑定证据
     * @param blocker 阻塞详情
     */
    public PlanStep(
            String id,
            String description,
            String verification,
            PlanStepStatus status,
            List<String> evidenceToolCallIds,
            PlanBlocker blocker
    ) {
        this(
                id,
                description,
                verification,
                PlanEvidenceType.GENERAL,
                status,
                status == PlanStepStatus.PENDING ? -1 : 0,
                evidenceToolCallIds,
                blocker
        );
    }

    /**
     * 使用新状态和证据生成不可变步骤，保留描述和验收条件。
     *
     * @param nextStatus 下一状态
     * @param evidenceIds 支撑下一状态的工具调用 ID
     * @param nextBlocker 阻塞详情；非 BLOCKED 状态应为 {@code null}
     * @param nextEvidenceFromToolStep 新证据窗口起始下标
     * @return 使用指定执行状态生成的新步骤
     */
    public PlanStep withState(
            PlanStepStatus nextStatus,
            List<String> evidenceIds,
            PlanBlocker nextBlocker,
            int nextEvidenceFromToolStep
    ) {
        return new PlanStep(
                id,
                description,
                verification,
                evidenceType,
                nextStatus,
                nextEvidenceFromToolStep,
                evidenceIds,
                nextBlocker
        );
    }
}
