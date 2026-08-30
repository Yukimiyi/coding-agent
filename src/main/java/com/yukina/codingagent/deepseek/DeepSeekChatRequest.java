package com.yukina.codingagent.deepseek;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * DeepSeek Chat Completions 请求体。
 *
 * @param model 模型名称
 * @param messages 对话消息
 * @param tools 可调用工具定义
 * @param thinking 思考模式配置
 * @param stream 是否使用流式响应
 * @param streamOptions 流式 Token 用量返回配置
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeepSeekChatRequest(
        String model,
        List<DeepSeekMessage> messages,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<DeepSeekToolDefinition> tools,
        Thinking thinking,
        boolean stream,
        @JsonProperty("stream_options") StreamOptions streamOptions
) {

    /**
     * DeepSeek 思考模式配置。
     *
     * @param type 模式类型
     */
    public record Thinking(String type) {
    }

    /**
     * DeepSeek 流式响应附加配置。
     *
     * @param includeUsage 是否在结束前返回完整 Token 用量
     */
    public record StreamOptions(@JsonProperty("include_usage") boolean includeUsage) {
    }
}
