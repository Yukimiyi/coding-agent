package com.yukina.codingagent.conversation.memory;

import com.yukina.codingagent.deepseek.DeepSeekMessage;

import java.util.List;
import java.util.Optional;

/**
 * 会话热上下文存储抽象，可由本地内存或 Redis 实现。
 */
public interface ConversationMemoryStore {

    /**
     * 读取会话上下文；缓存未命中或过期时返回空。
     *
     * @param conversationId 会话 ID
     * @return 不可变上下文列表；未命中时为空 Optional
     */
    Optional<List<DeepSeekMessage>> get(String conversationId);

    /**
     * 写入会话上下文的不可变快照。
     *
     * @param conversationId 会话 ID
     * @param messages 已裁剪模型消息
     */
    void put(String conversationId, List<DeepSeekMessage> messages);

    /**
     * 删除指定会话的热上下文。
     *
     * @param conversationId 会话 ID
     */
    void delete(String conversationId);
}
