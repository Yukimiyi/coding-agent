package com.yukina.codingagent.deepseek;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeepSeekMessage(
        String role,
        String content,
        @JsonProperty("reasoning_content") String reasoningContent,
        @JsonProperty("tool_calls") List<DeepSeekToolCall> toolCalls,
        @JsonProperty("tool_call_id") String toolCallId
) {

    public DeepSeekMessage {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("message role must not be blank");
        }
        toolCalls = toolCalls == null ? null : List.copyOf(toolCalls);
        if ("tool".equals(role) && (toolCallId == null || toolCallId.isBlank())) {
            throw new IllegalArgumentException("tool message must contain tool_call_id");
        }
    }

    public static DeepSeekMessage system(String content) {
        return text("system", content);
    }

    public static DeepSeekMessage user(String content) {
        return text("user", content);
    }

    public static DeepSeekMessage assistant(
            String content,
            String reasoningContent,
            List<DeepSeekToolCall> toolCalls
    ) {
        return new DeepSeekMessage("assistant", content, reasoningContent, toolCalls, null);
    }

    public static DeepSeekMessage tool(String toolCallId, String content) {
        if (content == null) {
            throw new IllegalArgumentException("tool result content must not be null");
        }
        return new DeepSeekMessage("tool", content, null, null, toolCallId);
    }

    private static DeepSeekMessage text(String role, String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException(role + " message content must not be blank");
        }
        return new DeepSeekMessage(role, content, null, null, null);
    }
}
