package com.yukina.codingagent.deepseek;

import java.util.Map;

public record DeepSeekToolDefinition(
        String type,
        FunctionDefinition function
) {

    public static DeepSeekToolDefinition function(
            String name,
            String description,
            Map<String, Object> parameters
    ) {
        if (name == null || !name.matches("[a-zA-Z0-9_-]+")) {
            throw new IllegalArgumentException("tool name must contain only letters, numbers, underscores, or hyphens");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("tool description must not be blank");
        }
        if (parameters == null || parameters.isEmpty()) {
            throw new IllegalArgumentException("tool parameters must not be empty");
        }
        return new DeepSeekToolDefinition(
                "function",
                new FunctionDefinition(name, description, Map.copyOf(parameters))
        );
    }

    public record FunctionDefinition(
            String name,
            String description,
            Map<String, Object> parameters
    ) {
    }
}
