package com.yukina.codingagent.tool;

import com.yukina.codingagent.deepseek.DeepSeekToolDefinition;
import tools.jackson.databind.JsonNode;

/**
 * Agent 可调用工具的统一扩展接口。
 */
public interface AgentTool {

    /**
     * 返回发送给模型的工具名称、描述和参数 Schema。
     *
     * @return DeepSeek function calling 协议使用的工具定义
     */
    DeepSeekToolDefinition definition();

    /**
     * 执行已解析为 JSON 对象的工具参数。
     *
     * @param arguments 工具参数
     * @return 可回传给模型的 JSON 或文本结果
     * @throws Exception 工具执行失败
     */
    String execute(JsonNode arguments) throws Exception;
}
