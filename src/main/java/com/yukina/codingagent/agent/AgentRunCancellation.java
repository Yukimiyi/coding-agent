package com.yukina.codingagent.agent;

/**
 * 为 Agent 执行提供协作式取消信号。
 */
@FunctionalInterface
public interface AgentRunCancellation {

    /** 永不触发取消的默认信号。 */
    AgentRunCancellation NONE = () -> false;

    /**
     * 判断当前执行是否已收到取消请求。
     *
     * @return 已请求取消时返回 {@code true}
     */
    boolean isCancellationRequested();

    /**
     * 在取消或线程中断时终止当前执行。
     */
    default void throwIfCancellationRequested() {
        if (isCancellationRequested() || Thread.currentThread().isInterrupted()) {
            throw new AgentRunCancelledException();
        }
    }
}
