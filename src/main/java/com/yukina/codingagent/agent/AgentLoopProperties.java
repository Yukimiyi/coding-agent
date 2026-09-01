package com.yukina.codingagent.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 循环的边界与系统提示词配置。
 *
 * @param maxIterations 单次任务允许的最大模型调用轮数
 * @param maxToolCallsPerIteration 单轮允许的最大工具调用数
 * @param traceContentLimit 执行轨迹中参数和结果的最大字符数
 * @param systemPrompt 发送给模型的系统提示词
 */
@ConfigurationProperties(prefix = "agent.loop")
public record AgentLoopProperties(
        int maxIterations,
        int maxToolCallsPerIteration,
        int traceContentLimit,
        String systemPrompt
) {

    /**
     * 校验循环配置，防止无界执行或空系统提示词。
     *
     * @throws IllegalArgumentException 任一数量上限非正数或系统提示词为空时抛出
     */
    public AgentLoopProperties {
        if (maxIterations <= 0) {
            throw new IllegalArgumentException("agent.loop.max-iterations must be positive");
        }
        if (maxToolCallsPerIteration <= 0) {
            throw new IllegalArgumentException("agent.loop.max-tool-calls-per-iteration must be positive");
        }
        if (traceContentLimit <= 0) {
            throw new IllegalArgumentException("agent.loop.trace-content-limit must be positive");
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("agent.loop.system-prompt must not be blank");
        }
    }
}
