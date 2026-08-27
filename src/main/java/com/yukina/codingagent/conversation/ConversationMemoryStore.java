package com.yukina.codingagent.conversation;

import com.yukina.codingagent.deepseek.DeepSeekMessage;

import java.util.List;
import java.util.Optional;

public interface ConversationMemoryStore {

    Optional<List<DeepSeekMessage>> get(String conversationId);

    void put(String conversationId, List<DeepSeekMessage> messages);

    void delete(String conversationId);
}
