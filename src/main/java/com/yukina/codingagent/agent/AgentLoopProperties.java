package com.yukina.codingagent.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.loop")
public record AgentLoopProperties(
        int maxIterations,
        int maxToolCallsPerIteration,
        int traceContentLimit,
        String systemPrompt
) {

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
