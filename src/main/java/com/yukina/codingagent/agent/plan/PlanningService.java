package com.yukina.codingagent.agent.plan;

import com.yukina.codingagent.agent.perception.ProjectSnapshot;
import com.yukina.codingagent.deepseek.DeepSeekMessage;

import java.util.List;

/** 为 CODE 任务生成一次无工具、公开且可验证的实施计划。 */
public interface PlanningService {

    /**
     * @param task 当前用户任务
     * @param history 已裁剪的 user/assistant 对话历史
     * @param snapshot 程序采集的受限项目快照
     * @return 规范化计划及模型用量
     */
    PlanningResult createPlan(
            String task,
            List<DeepSeekMessage> history,
            ProjectSnapshot snapshot
    );
}
