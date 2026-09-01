package com.yukina.codingagent.agent.run;

import com.yukina.codingagent.agent.AgentRunResult;
import com.yukina.codingagent.agent.plan.AgentPlan;
import com.yukina.codingagent.conversation.model.ConversationMode;

import java.time.Instant;
import java.util.List;

/**
 * 供状态查询与页面恢复使用的异步任务快照。
 *
 * @param runId 运行 ID
 * @param requestId 客户端幂等请求 ID
 * @param conversationId 会话 ID
 * @param mode CHAT 或 CODE 会话模式
 * @param conversationCreated 本次提交是否创建了会话
 * @param status 当前生命周期状态
 * @param createdAt 创建时间
 * @param startedAt 实际开始时间
 * @param finishedAt 终止时间
 * @param currentIteration 当前或最后模型轮次
 * @param toolSteps 已完成工具轨迹
 * @param plan 当前 CODE 运行的最新计划
 * @param processTrace 当前可恢复的公开工作过程
 * @param liveContent 当前实时回答缓冲区
 * @param result 正常完成时的 Agent 结果
 * @param error 失败或取消时的安全错误说明
 * @param lastSequence 最近事件序号
 */
public record AgentRunSnapshot(
        String runId,
        String requestId,
        String conversationId,
        ConversationMode mode,
        boolean conversationCreated,
        AgentRunStatus status,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        int currentIteration,
        List<AgentRunResult.ToolStep> toolSteps,
        AgentPlan plan,
        List<AgentRunResult.ProcessEntry> processTrace,
        String liveContent,
        AgentRunResult result,
        String error,
        long lastSequence
) {
    /** 对工具轨迹创建不可变副本。 */
    public AgentRunSnapshot {
        toolSteps = toolSteps == null ? List.of() : List.copyOf(toolSteps);
        processTrace = processTrace == null ? List.of() : List.copyOf(processTrace);
    }
}
