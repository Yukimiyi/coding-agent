package com.yukina.codingagent.deepseek;

import java.util.List;

public record DeepSeekChatRequest(
        String model,
        List<Message> messages,
        Thinking thinking,
        boolean stream
) {

    public record Message(String role, String content) {
    }

    public record Thinking(String type) {
    }
}
