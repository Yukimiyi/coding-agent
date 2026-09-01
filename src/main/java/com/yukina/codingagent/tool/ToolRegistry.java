package com.yukina.codingagent.tool;

import com.yukina.codingagent.deepseek.DeepSeekToolDefinition;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 汇总 Spring 容器中的工具，并按名称提供查询和协议定义。
 */
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools;
    private final List<DeepSeekToolDefinition> definitions;

    /**
     * 注册所有工具并拒绝重复名称，保证模型调用具有唯一目标。
     *
     * @param registeredTools Spring 容器发现的全部工具实现
     * @throws IllegalStateException 工具定义为空、名称为空或名称重复时抛出
     */
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

    /**
     * 按工具名称查询实现。
     *
     * @param name 工具定义中的 function 名称
     * @return 匹配工具；名称为空或不存在时返回空 Optional
     */
    public Optional<AgentTool> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tools.get(name));
    }

    /** @return 按注册顺序生成的不可变工具定义列表 */
    public List<DeepSeekToolDefinition> definitions() {
        return definitions;
    }

    /**
     * 从工具定义中提取并校验唯一名称。
     *
     * @param tool 待注册工具
     * @return 非空 function 名称
     * @throws IllegalStateException 工具或定义不完整时抛出
     */
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
