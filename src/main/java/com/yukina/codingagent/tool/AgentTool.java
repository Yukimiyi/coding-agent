package com.yukina.codingagent.tool;

import com.yukina.codingagent.deepseek.DeepSeekToolDefinition;
import tools.jackson.databind.JsonNode;

public interface AgentTool {

    DeepSeekToolDefinition definition();

    String execute(JsonNode arguments) throws Exception;
}
