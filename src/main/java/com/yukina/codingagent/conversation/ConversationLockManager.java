package com.yukina.codingagent.conversation;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Component
public class ConversationLockManager {

    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

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
}
