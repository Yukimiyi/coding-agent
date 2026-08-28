package com.yukina.codingagent.deepseek;

import java.util.Map;

/**
 * 发送给 DeepSeek 的工具定义。
 *
 * @param type 工具类型
 * @param function 函数定义
 */
public record DeepSeekToolDefinition(
        String type,
        FunctionDefinition function
) {

    /**
     * 校验并创建函数工具定义。
     */
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

    /**
     * 函数工具的名称、描述和 JSON Schema 参数定义。
     */
    public record FunctionDefinition(
            String name,
            String description,
            Map<String, Object> parameters
    ) {
    }
}
