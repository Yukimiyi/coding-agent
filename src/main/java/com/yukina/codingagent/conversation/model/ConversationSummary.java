package com.yukina.codingagent.conversation.model;

import java.time.Instant;

/**
 * 一个会话已经压缩的长期记忆快照。
 *
 * @param conversationId 会话 ID
 * @param summary 结构化 JSON 摘要
 * @param lastMessageId 摘要已经覆盖的最后一条成功消息 ID
 * @param updatedAt 最近更新时间
 */
public record ConversationSummary(
        String conversationId,
        String summary,
        long lastMessageId,
        Instant updatedAt
) {
}
