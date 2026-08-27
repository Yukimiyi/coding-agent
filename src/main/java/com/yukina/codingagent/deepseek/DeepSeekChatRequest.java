package com.yukina.codingagent.deepseek;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeepSeekChatRequest(
        String model,
        List<DeepSeekMessage> messages,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<DeepSeekToolDefinition> tools,
        Thinking thinking,
        boolean stream
) {

    public record Thinking(String type) {
    }
}
