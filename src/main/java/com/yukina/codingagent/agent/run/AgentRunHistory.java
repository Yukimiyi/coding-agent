package com.yukina.codingagent.agent.run;

import com.yukina.codingagent.agent.AgentRunResult;
import com.yukina.codingagent.conversation.model.ConversationMode;

import java.time.Instant;
import java.util.List;

/**
 * 持久化的终态 Agent 运行摘要，用于重启后恢复工具轨迹。
 *
 * @param runId 运行 ID
 * @param requestId 客户端幂等请求 ID
 * @param conversationId 会话 ID
 * @param mode CHAT 或 CODE 会话模式
 * @param status 终态状态
 * @param createdAt 创建时间
 * @param startedAt 实际开始时间
 * @param finishedAt 终止时间
 * @param toolSteps 完整工具轨迹
 * @param result 可选 Agent 结果
 * @param error 可选失败或取消说明
 */
public record AgentRunHistory(
        String runId,
        String requestId,
        String conversationId,
        ConversationMode mode,
        AgentRunStatus status,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        List<AgentRunResult.ToolStep> toolSteps,
        AgentRunResult result,
        String error
) {
    /** 对工具轨迹创建不可变快照。 */
    public AgentRunHistory {
        toolSteps = toolSteps == null ? List.of() : List.copyOf(toolSteps);
    }
}
