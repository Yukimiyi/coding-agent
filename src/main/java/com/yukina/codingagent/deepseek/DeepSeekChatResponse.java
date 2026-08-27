package com.yukina.codingagent.deepseek;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DeepSeekChatResponse(
        String id,
        String model,
        List<Choice> choices,
        Usage usage
) {

    public String firstContent() {
        if (choices == null || choices.isEmpty() || choices.getFirst().message() == null) {
            throw new DeepSeekApiException("DeepSeek API returned no choices", null);
        }
        return choices.getFirst().message().content();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(
            int index,
            Message message,
            @JsonProperty("finish_reason") String finishReason
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(
            String role,
            String content,
            @JsonProperty("reasoning_content") String reasoningContent
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("total_tokens") int totalTokens
    ) {
    }
}
