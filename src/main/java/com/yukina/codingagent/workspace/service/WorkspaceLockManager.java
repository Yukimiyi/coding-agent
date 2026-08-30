package com.yukina.codingagent.workspace.service;

import com.yukina.codingagent.agent.AgentRunCancelledException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 串行化同一项目中的 Agent 运行，避免多个对话同时修改共享文件。
 */
@Component
public class WorkspaceLockManager {

    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** 在项目专属锁中执行同步任务。 */
    public <T> T withLock(String workspaceId, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(workspaceId, id -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** 可中断地等待项目锁，使排队中的后台任务能够响应取消。 */
    public <T> T withInterruptibleLock(String workspaceId, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(workspaceId, id -> new ReentrantLock());
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
        }
    }
}
