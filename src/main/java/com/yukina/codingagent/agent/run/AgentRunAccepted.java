package com.yukina.codingagent.agent.run;

import com.yukina.codingagent.conversation.model.ConversationMode;

/**
 * 异步任务提交成功后立即返回的定位信息。
 *
 * @param runId 运行 ID
 * @param conversationId 会话 ID
 * @param mode CHAT 或 CODE 会话模式
 * @param conversationCreated 本次提交是否创建了会话
 * @param status 初始运行状态
 * @param statusUrl 状态查询地址
 * @param eventsUrl SSE 事件订阅地址
 */
public record AgentRunAccepted(
        String runId,
        String conversationId,
        ConversationMode mode,
        boolean conversationCreated,
        AgentRunStatus status,
        String statusUrl,
        String eventsUrl
) {
}
