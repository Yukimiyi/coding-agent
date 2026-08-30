package com.yukina.codingagent.agent.run;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 异步任务的保留数量、保留时间与 SSE 连接时限。
 */
@ConfigurationProperties(prefix = "agent.runs")
public record AgentRunProperties(Duration retention, int maxRuns, Duration sseTimeout) {

    /** 校验异步运行配置。 */
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
    }
}
