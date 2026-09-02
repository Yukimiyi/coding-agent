package com.yukina.codingagent.conversation.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 增量滚动摘要的触发和字符边界。
 *
 * @param enabled 是否启用长期摘要
 * @param triggerMessages 未摘要成功消息达到该数量时触发
 * @param triggerContentChars 未摘要消息字符数达到该值时触发
 * @param maxContextChars 摘要模型输入的最大字符数
 * @param maxSummaryChars 持久化摘要的最大字符数
 * @param maxBatchMessages 单次读取的最大未摘要消息数
 * @param systemPrompt 摘要模型的结构化输出约束
 */
@ConfigurationProperties(prefix = "agent.context.summary")
public record ConversationSummaryProperties(
        boolean enabled,
        int triggerMessages,
        int triggerContentChars,
        int maxContextChars,
        int maxSummaryChars,
        int maxBatchMessages,
        String systemPrompt
) {

    /** 校验摘要阈值、上下文预算和提示词。 */
    public ConversationSummaryProperties {
        if (triggerMessages <= 0 || triggerContentChars <= 0 || maxContextChars <= 0
                || maxSummaryChars <= 0 || maxBatchMessages <= 0) {
            throw new IllegalArgumentException("conversation summary limits must be positive");
        }
        if (maxContextChars < maxSummaryChars) {
            throw new IllegalArgumentException("summary context must not be smaller than summary output");
        }
        if (maxBatchMessages < triggerMessages) {
            throw new IllegalArgumentException("summary batch must cover the message trigger");
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("summary system prompt must not be blank");
        }
    }
}
