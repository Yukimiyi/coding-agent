package com.yukina.codingagent.conversation;

import com.yukina.codingagent.agent.AgentRunResult;

public record ConversationChatResult(
        String conversationId,
        boolean conversationCreated,
        AgentRunResult result
) {
}
