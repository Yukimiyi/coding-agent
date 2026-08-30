package com.yukina.codingagent.agent.run;

/**
 * 异步任务提交成功后立即返回的定位信息。
 */
public record AgentRunAccepted(
        String runId,
        String conversationId,
        String workspaceId,
        boolean conversationCreated,
        AgentRunStatus status,
        String statusUrl,
        String eventsUrl
) {
}
