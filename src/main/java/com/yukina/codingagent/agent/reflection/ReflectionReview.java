package com.yukina.codingagent.agent.reflection;

import com.yukina.codingagent.deepseek.DeepSeekChatResponse;

/**
 * 反思结论及其模型用量。
 *
 * @param feedback 结构化审查结论
 * @param usage 本次审查调用消耗的 Token；服务未返回时可为空
 */
public record ReflectionReview(
        ReflectionFeedback feedback,
        DeepSeekChatResponse.Usage usage
) {

    /**
     * 校验反思反馈并保留关联模型用量。
     *
     * @throws IllegalArgumentException feedback 为空时抛出
     */
    public ReflectionReview {
        if (feedback == null) {
            throw new IllegalArgumentException("reflection feedback must not be null");
        }
    }
}
