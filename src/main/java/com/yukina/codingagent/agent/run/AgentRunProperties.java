package com.yukina.codingagent.agent.run;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 异步任务的保留数量、保留时间、SSE 连接时限和单次运行内存边界。
 *
 * @param retention 终态运行在内存注册表中的保留时长
 * @param maxRuns 内存中最多保留的运行数量
 * @param sseTimeout 单个 SSE 连接超时时长
 * @param maxEventsPerRun 单次运行最多保留的可重放事件数量
 * @param maxLiveContentChars 实时回答缓冲区最大字符数
 */
@ConfigurationProperties(prefix = "agent.runs")
public record AgentRunProperties(
        Duration retention,
        int maxRuns,
        Duration sseTimeout,
        int maxEventsPerRun,
        int maxLiveContentChars
) {

    /**
     * 校验异步运行配置。
     *
     * @throws IllegalArgumentException 任一时间或数量边界非正数时抛出
     */
    public AgentRunProperties {
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("agent.runs.retention must be positive");
        }
        if (maxRuns <= 0) {
            throw new IllegalArgumentException("agent.runs.max-runs must be positive");
        }
        if (sseTimeout == null || sseTimeout.isNegative() || sseTimeout.isZero()) {
            throw new IllegalArgumentException("agent.runs.sse-timeout must be positive");
        }
        if (maxEventsPerRun <= 0 || maxLiveContentChars <= 0) {
            throw new IllegalArgumentException("agent run memory limits must be positive");
        }
    }
}
