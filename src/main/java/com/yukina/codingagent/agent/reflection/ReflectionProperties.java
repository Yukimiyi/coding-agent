package com.yukina.codingagent.agent.reflection;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 结束前反思审查的配置。
 *
 * @param maxRounds 单次 Agent 运行最多执行的反思次数；为 0 时禁用
 * @param maxContextChars 发送给审查模型的证据文本最大字符数
 * @param systemPrompt 约束审查模型只返回结构化结论的系统提示词
 */
@ConfigurationProperties(prefix = "agent.reflection")
public record ReflectionProperties(
        int maxRounds,
        int maxContextChars,
        String systemPrompt
) {

    /**
     * 校验反思边界，避免无界审查或空提示词。
     *
     * @throws IllegalArgumentException 次数为负、上下文上限非正或提示词为空时抛出
     */
    public ReflectionProperties {
        if (maxRounds < 0) {
            throw new IllegalArgumentException("agent.reflection.max-rounds must not be negative");
        }
        if (maxContextChars <= 0) {
            throw new IllegalArgumentException("agent.reflection.max-context-chars must be positive");
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("agent.reflection.system-prompt must not be blank");
        }
    }
}
