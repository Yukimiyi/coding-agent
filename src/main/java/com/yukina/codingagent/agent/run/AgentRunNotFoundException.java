package com.yukina.codingagent.agent.run;

/**
 * 表示请求的异步任务不存在或已经超过保留期限。
 */
public class AgentRunNotFoundException extends RuntimeException {

    /** 使用指定运行 ID 创建异常。 */
    public AgentRunNotFoundException(String runId) {
        super("Agent run not found: " + runId);
    }
}
