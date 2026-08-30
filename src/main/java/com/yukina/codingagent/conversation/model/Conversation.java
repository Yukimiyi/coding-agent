package com.yukina.codingagent.conversation.model;

import java.time.Instant;

/**
 * 会话元数据。
 *
 * @param id 会话 ID
 * @param title 会话标题
 * @param workspaceId 会话绑定的工作空间 ID
 * @param createdAt 创建时间
 * @param updatedAt 最近活动时间
 */
public record Conversation(
        String id,
        String title,
        String workspaceId,
        Instant createdAt,
        Instant updatedAt
) {
}
