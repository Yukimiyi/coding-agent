package com.yukina.codingagent.conversation.model;

import com.yukina.codingagent.agent.AgentRunResult;

/**
 * 有状态对话接口的返回结果。
 *
 * @param conversationId 当前会话 ID
 * @param conversationCreated 本次请求是否新建了会话
 * @param result Agent 执行结果
 */
public record ConversationChatResult(
        String conversationId,
        boolean conversationCreated,
        AgentRunResult result
) {
}
