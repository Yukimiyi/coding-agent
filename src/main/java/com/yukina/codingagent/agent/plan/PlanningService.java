package com.yukina.codingagent.agent.plan;

import com.yukina.codingagent.agent.perception.ProjectSnapshot;
import com.yukina.codingagent.deepseek.DeepSeekMessage;

import java.util.List;

/** 为 CODE 任务生成一次无工具、公开且可验证的实施计划。 */
public interface PlanningService {

    /**
     * 根据当前任务、历史和项目感知快照创建公开实施计划。
     *
     * @param task 当前用户任务
     * @param history 已裁剪的长期摘要及 user/assistant 对话历史
     * @param snapshot 程序采集的受限项目快照
     * @return 规范化计划及模型用量
     */
    PlanningResult createPlan(
            String task,
            List<DeepSeekMessage> history,
            ProjectSnapshot snapshot
    );
}
