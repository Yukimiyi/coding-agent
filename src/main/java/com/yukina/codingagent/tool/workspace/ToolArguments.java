package com.yukina.codingagent.tool.workspace;

import com.yukina.codingagent.tool.ToolExecutionException;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 提供工具 JSON 参数的类型校验和默认值读取方法。
 */
public final class ToolArguments {

    /** 禁止实例化参数工具类。 */
    private ToolArguments() {
    }

    /** 读取必填且非空的字符串参数。 */
    public static String requiredText(JsonNode arguments, String name) {
        return requiredText(arguments, name, false);
    }

    /** 按是否允许空字符串读取必填文本参数。 */
    public static String requiredText(JsonNode arguments, String name, boolean allowEmpty) {
        JsonNode value = arguments.get(name);
        if (value == null || !value.isTextual() || (!allowEmpty && value.asText().isBlank())) {
            throw new ToolExecutionException("INVALID_ARGUMENTS", name + " must be a string"
                    + (allowEmpty ? "" : " and must not be blank"));
        }
        return value.asText();
    }

    /** 读取可选字符串参数，缺失时返回默认值。 */
    public static String optionalText(JsonNode arguments, String name, String defaultValue) {
        JsonNode value = arguments.get(name);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isTextual()) {
            throw new ToolExecutionException("INVALID_ARGUMENTS", name + " must be a string");
        }
        return value.asText();
    }

    /** 读取可选布尔参数，缺失时返回默认值。 */
    public static boolean optionalBoolean(JsonNode arguments, String name, boolean defaultValue) {
        JsonNode value = arguments.get(name);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isBoolean()) {
            throw new ToolExecutionException("INVALID_ARGUMENTS", name + " must be a boolean");
        }
        return value.asBoolean();
    }

    /** 读取限定闭区间的可选整数参数。 */
    public static int optionalInt(JsonNode arguments, String name, int defaultValue, int min, int max) {
        JsonNode value = arguments.get(name);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new ToolExecutionException("INVALID_ARGUMENTS", name + " must be an integer");
        }
        int number = value.asInt();
        if (number < min || number > max) {
            throw new ToolExecutionException(
                    "INVALID_ARGUMENTS",
                    name + " must be between " + min + " and " + max
            );
        }
        return number;
    }

    /** 读取由非空字符串组成且数量受限的必填数组参数。 */
    public static List<String> requiredTextList(JsonNode arguments, String name, int maxItems) {
        JsonNode value = arguments.get(name);
        if (value == null || !value.isArray() || value.isEmpty()) {
            throw new ToolExecutionException("INVALID_ARGUMENTS", name + " must be a non-empty string array");
        }
        if (value.size() > maxItems) {
            throw new ToolExecutionException(
                    "INVALID_ARGUMENTS",
                    name + " must contain at most " + maxItems + " items"
            );
        }
        List<String> items = new ArrayList<>(value.size());
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new ToolExecutionException(
                        "INVALID_ARGUMENTS",
                        name + " must contain only non-blank strings"
                );
            }
            items.add(item.asText());
        }
        return List.copyOf(items);
    }
}
