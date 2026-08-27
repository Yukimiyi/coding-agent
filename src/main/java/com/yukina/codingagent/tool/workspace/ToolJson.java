package com.yukina.codingagent.tool.workspace;

import com.yukina.codingagent.tool.ToolExecutionException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public final class ToolJson {

    private ToolJson() {
    }

    public static String serialize(ObjectMapper objectMapper, Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new ToolExecutionException("RESULT_SERIALIZATION_FAILED", "Failed to serialize tool result");
        }
    }
}
