package com.yukina.codingagent.agent;

/**
 * 表示 Agent 执行已由用户主动取消。
 */
public class AgentRunCancelledException extends RuntimeException {

    /** 创建标准取消异常。 */
    public AgentRunCancelledException() {
        super("Agent execution cancelled");
    }
}
