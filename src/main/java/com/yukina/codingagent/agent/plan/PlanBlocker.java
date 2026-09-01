package com.yukina.codingagent.agent.plan;

import java.util.List;

/**
 * 经过程序证据校验的步骤阻塞说明。
 *
 * @param reasonCode 稳定阻塞原因码
 * @param reason 人类可读原因
 * @param evidenceToolCallIds 支撑该判断的失败工具调用 ID
 * @param resolution 需要用户或环境完成的解除方式
 */
public record PlanBlocker(
        String reasonCode,
        String reason,
        List<String> evidenceToolCallIds,
        String resolution
) {

    /** 复制证据 ID，避免外部修改。 */
    public PlanBlocker {
        evidenceToolCallIds = evidenceToolCallIds == null ? List.of() : List.copyOf(evidenceToolCallIds);
    }
}
