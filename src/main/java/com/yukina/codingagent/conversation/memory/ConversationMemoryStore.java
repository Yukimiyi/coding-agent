package com.yukina.codingagent.conversation.memory;

import com.yukina.codingagent.deepseek.DeepSeekMessage;

import java.util.List;
import java.util.Optional;

/**
 * 会话热上下文存储抽象，可由本地内存或 Redis 实现。
 */
public interface ConversationMemoryStore {

    /** 读取会话上下文；缓存未命中或过期时返回空。 */
    Optional<List<DeepSeekMessage>> get(String conversationId);

    /** 写入会话上下文的不可变快照。 */
    void put(String conversationId, List<DeepSeekMessage> messages);

    /** 删除指定会话的热上下文。 */
    void delete(String conversationId);
}
