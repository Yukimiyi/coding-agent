package com.yukina.codingagent.tool;

import com.yukina.codingagent.deepseek.DeepSeekToolDefinition;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools;
    private final List<DeepSeekToolDefinition> definitions;

    public ToolRegistry(List<AgentTool> registeredTools) {
        Map<String, AgentTool> toolsByName = new LinkedHashMap<>();
        for (AgentTool tool : registeredTools) {
            String name = toolName(tool);
            AgentTool existing = toolsByName.putIfAbsent(name, tool);
            if (existing != null) {
                throw new IllegalStateException("Duplicate tool name: " + name);
            }
        }
        this.tools = Map.copyOf(toolsByName);
        this.definitions = toolsByName.values().stream()
                .map(AgentTool::definition)
                .toList();
    }

    public Optional<AgentTool> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tools.get(name));
    }

    public List<DeepSeekToolDefinition> definitions() {
        return definitions;
    }

    private static String toolName(AgentTool tool) {
        if (tool == null || tool.definition() == null || tool.definition().function() == null) {
            throw new IllegalStateException("Tool definition must not be null");
        }
        String name = tool.definition().function().name();
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("Tool name must not be blank");
        }
        return name;
    }
}
