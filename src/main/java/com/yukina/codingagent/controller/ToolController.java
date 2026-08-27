package com.yukina.codingagent.controller;

import com.yukina.codingagent.deepseek.DeepSeekToolCall;
import com.yukina.codingagent.deepseek.DeepSeekToolDefinition;
import com.yukina.codingagent.tool.ToolExecutionResult;
import com.yukina.codingagent.tool.ToolExecutor;
import com.yukina.codingagent.tool.ToolRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/tools")
public class ToolController {

    private final ToolRegistry registry;
    private final ToolExecutor executor;

    public ToolController(ToolRegistry registry, ToolExecutor executor) {
        this.registry = registry;
        this.executor = executor;
    }

    @GetMapping
    public List<DeepSeekToolDefinition> list() {
        return registry.definitions();
    }

    @PostMapping("/execute")
    public ToolExecutionResult execute(@RequestBody DeepSeekToolCall toolCall) {
        return executor.execute(toolCall);
    }
}
