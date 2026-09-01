package com.yukina.codingagent.tool.workspace;

import com.yukina.codingagent.tool.ToolExecutionException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 统一序列化工具结果并转换序列化异常。
 */
public final class ToolJson {

    /** 禁止实例化 JSON 工具类。 */
    private ToolJson() {
    }

    /**
     * 将工具结果序列化为 JSON 字符串。
     *
     * @param objectMapper Jackson 序列化器
     * @param value 待序列化工具结果
     * @return JSON 字符串
     * @throws ToolExecutionException Jackson 序列化失败时抛出
     */
    public static String serialize(ObjectMapper objectMapper, Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new ToolExecutionException("RESULT_SERIALIZATION_FAILED", "Failed to serialize tool result");
        }
    }
}
