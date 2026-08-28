package com.yukina.codingagent.conversation.model;

import java.util.List;

/**
 * 使用消息 ID 游标返回的一页历史消息。
 *
 * @param messages 按时间正序排列的当前页消息
 * @param nextCursor 下一页查询使用的 {@code beforeId}
 * @param hasMore 是否仍有更早消息
 */
public record MessagePage(
        List<ConversationMessage> messages,
        Long nextCursor,
        boolean hasMore
) {

    /** 将消息列表复制为不可变快照。 */
    public MessagePage {
        messages = List.copyOf(messages);
    }
}
