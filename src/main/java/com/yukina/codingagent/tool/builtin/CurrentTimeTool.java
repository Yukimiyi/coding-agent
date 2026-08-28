package com.yukina.codingagent.tool.builtin;

import com.yukina.codingagent.deepseek.DeepSeekToolDefinition;
import com.yukina.codingagent.tool.AgentTool;
import com.yukina.codingagent.tool.ToolExecutionException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 返回服务器当前本地时间和时区的只读工具。
 */
@Component
public class CurrentTimeTool implements AgentTool {

    private static final DeepSeekToolDefinition DEFINITION = DeepSeekToolDefinition.function(
            "get_current_time",
            "Get the current local date, time, and time zone.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(),
                    "additionalProperties", false
            )
    );

    private final ObjectMapper objectMapper;

    /** 创建当前时间工具。 */
    public CurrentTimeTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** {@inheritDoc} */
    @Override
    public DeepSeekToolDefinition definition() {
        return DEFINITION;
    }

    /** {@inheritDoc} */
    @Override
    public String execute(JsonNode arguments) {
        if (arguments.size() > 0) {
            throw new ToolExecutionException("INVALID_ARGUMENTS", "get_current_time does not accept arguments");
        }

        ZonedDateTime now = ZonedDateTime.now();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dateTime", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        result.put("zoneId", now.getZone().getId());
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JacksonException exception) {
            throw new ToolExecutionException("RESULT_SERIALIZATION_FAILED", "Failed to serialize current time");
        }
    }
}
