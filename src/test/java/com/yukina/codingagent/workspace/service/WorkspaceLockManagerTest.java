package com.yukina.codingagent.workspace.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证项目级运行串行化不会阻塞其他独立项目。 */
class WorkspaceLockManagerTest {

    private final WorkspaceLockManager lockManager = new WorkspaceLockManager();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** 关闭测试虚拟线程执行器。 */
    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /** 验证同一项目的第二个对话必须等待首个运行释放项目锁。 */
    @Test
    void serializesRunsInSameProject() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);

        executor.submit(() -> lockManager.withLock("project-1", () -> {
            firstEntered.countDown();
            await(releaseFirst);
            return null;
        }));
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS));

        executor.submit(() -> lockManager.withLock("project-1", () -> {
            secondEntered.countDown();
            return null;
        }));
        assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS));

        releaseFirst.countDown();
        assertTrue(secondEntered.await(1, TimeUnit.SECONDS));
    }

    /** 验证不同项目可以同时执行。 */
    @Test
    void allowsDifferentProjectsToRunInParallel() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);

        executor.submit(() -> lockManager.withLock("project-1", () -> {
            firstEntered.countDown();
            await(releaseFirst);
            return null;
        }));
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS));

        executor.submit(() -> lockManager.withLock("project-2", () -> {
            secondEntered.countDown();
            return null;
        }));
        assertTrue(secondEntered.await(1, TimeUnit.SECONDS));
        releaseFirst.countDown();
    }

    /** 在测试任务中等待门闩，并把中断转换为测试失败。 */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test thread interrupted", exception);
        }
    }
}
