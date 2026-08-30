package com.yukina.codingagent.agent.run;

/**
 * 表示任务提交与当前活跃运行发生冲突。
 */
public class AgentRunConflictException extends RuntimeException {

    /** 使用可直接返回客户端的安全说明创建异常。 */
    public AgentRunConflictException(String message) {
        super(message);
    }
}
