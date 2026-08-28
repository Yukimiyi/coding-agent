package com.yukina.codingagent.tool;

import com.yukina.codingagent.deepseek.DeepSeekToolDefinition;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证工具注册表的发现和唯一性约束。 */
class ToolRegistryTest {

    /** 验证注册工具可按名称查询且会暴露协议定义。 */
    @Test
    void registersToolsAndExposesTheirDefinitions() {
        AgentTool tool = tool("echo");

        ToolRegistry registry = new ToolRegistry(List.of(tool));

        assertSame(tool, registry.find("echo").orElseThrow());
        assertEquals("echo", registry.definitions().getFirst().function().name());
    }

    /** 验证重复工具名称会在启动阶段失败。 */
    @Test
    void rejectsDuplicateToolNames() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new ToolRegistry(List.of(tool("echo"), tool("echo")))
        );

        assertEquals("Duplicate tool name: echo", exception.getMessage());
    }

    /** 创建指定名称的轻量测试工具。 */
    private static AgentTool tool(String name) {
        return new AgentTool() {
            /** {@inheritDoc} */
            @Override
            public DeepSeekToolDefinition definition() {
                return DeepSeekToolDefinition.function(
                        name,
                        "Test tool",
                        Map.of("type", "object", "properties", Map.of())
                );
            }

            /** {@inheritDoc} */
            @Override
            public String execute(JsonNode arguments) {
                return "{}";
            }
        };
    }
}
