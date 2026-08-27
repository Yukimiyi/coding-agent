package com.yukina.codingagent.conversation;

import java.util.List;

public record MessagePage(
        List<ConversationMessage> messages,
        Long nextCursor,
        boolean hasMore
) {

    public MessagePage {
        messages = List.copyOf(messages);
    }
}
