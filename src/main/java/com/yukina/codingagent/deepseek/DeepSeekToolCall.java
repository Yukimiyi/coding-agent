package com.yukina.codingagent.deepseek;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 模型返回的一次工具调用请求。
 *
 * @param id 调用 ID
 * @param type 工具类型，当前仅支持 {@code function}
 * @param function 函数名称和 JSON 参数
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeepSeekToolCall(
        String id,
        String type,
        FunctionCall function
) {

    /**
     * 函数工具调用载荷。
     *
     * @param name 工具名称
     * @param arguments JSON 字符串形式的参数
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FunctionCall(String name, String arguments) {
    }
}
