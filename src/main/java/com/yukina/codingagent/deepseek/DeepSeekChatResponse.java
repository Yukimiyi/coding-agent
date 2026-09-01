package com.yukina.codingagent.deepseek;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * DeepSeek Chat Completions 响应体。
 *
 * @param id 响应 ID
 * @param model 实际使用的模型
 * @param choices 候选回复
 * @param usage Token 用量
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeepSeekChatResponse(
        String id,
        String model,
        List<Choice> choices,
        Usage usage
) {

    /**
     * 返回第一个候选回复的文本内容。
     *
     * @return 第一个候选消息的公开内容
     * @throws DeepSeekApiException 响应没有有效候选消息时抛出
     */
    public String firstContent() {
        return firstMessage().content();
    }

    /**
     * 返回第一个候选消息，并在响应缺少候选项时抛出协议异常。
     *
     * @return 第一个候选消息
     * @throws DeepSeekApiException 响应没有有效候选消息时抛出
     */
    public DeepSeekMessage firstMessage() {
        if (choices == null || choices.isEmpty() || choices.getFirst().message() == null) {
            throw new DeepSeekApiException("DeepSeek API returned no choices", null);
        }
        return choices.getFirst().message();
    }

    /**
     * 返回第一个候选回复的停止原因。
     *
     * @return stop、tool_calls、length 等停止原因
     * @throws DeepSeekApiException 响应没有候选项时抛出
     */
    public String firstFinishReason() {
        if (choices == null || choices.isEmpty()) {
            throw new DeepSeekApiException("DeepSeek API returned no choices", null);
        }
        return choices.getFirst().finishReason();
    }

    /**
     * 单个模型候选回复。
     *
     * @param index 候选索引
     * @param message 候选消息
     * @param finishReason 停止原因
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(
            int index,
            DeepSeekMessage message,
            @JsonProperty("finish_reason") String finishReason
    ) {
    }

    /**
     * 单次模型响应的 Token 用量。
     *
     * @param promptTokens 输入 Token 数
     * @param completionTokens 输出 Token 数
     * @param totalTokens 总 Token 数
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("total_tokens") int totalTokens
    ) {
    }
}
