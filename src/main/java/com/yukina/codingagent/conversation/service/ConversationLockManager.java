package com.yukina.codingagent.conversation.service;

import com.yukina.codingagent.agent.AgentRunCancelledException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 为同一会话串行化请求，防止消息顺序和上下文窗口发生竞态。
 */
@Component
public class ConversationLockManager {

    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * 在会话专属锁内执行操作，并在无等待者时回收锁对象。
     */
    public <T> T withLock(String conversationId, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(conversationId, id -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
            if (!lock.isLocked() && !lock.hasQueuedThreads()) {
                locks.remove(conversationId, lock);
            }
        }
    }

    /**
     * 可中断地等待会话锁，使排队中的异步任务能够响应取消请求。
     */
    public <T> T withInterruptibleLock(String conversationId, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(conversationId, id -> new ReentrantLock());
        boolean acquired = false;
        try {
            lock.lockInterruptibly();
            acquired = true;
            return action.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AgentRunCancelledException();
        } finally {
            if (acquired) {
                lock.unlock();
            }
            if (!lock.isLocked() && !lock.hasQueuedThreads()) {
                locks.remove(conversationId, lock);
            }
        }
    }
}
