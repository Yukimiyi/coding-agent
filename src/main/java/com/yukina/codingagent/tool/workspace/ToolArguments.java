package com.yukina.codingagent.tool.workspace;

import com.yukina.codingagent.tool.ToolExecutionException;
import tools.jackson.databind.JsonNode;

public final class ToolArguments {

    private ToolArguments() {
    }

    public static String requiredText(JsonNode arguments, String name) {
        return requiredText(arguments, name, false);
    }

    public static String requiredText(JsonNode arguments, String name, boolean allowEmpty) {
        JsonNode value = arguments.get(name);
        if (value == null || !value.isTextual() || (!allowEmpty && value.asText().isBlank())) {
            throw new ToolExecutionException("INVALID_ARGUMENTS", name + " must be a string"
                    + (allowEmpty ? "" : " and must not be blank"));
        }
        return value.asText();
    }

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
}
