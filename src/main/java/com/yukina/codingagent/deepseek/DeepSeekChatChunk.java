package com.yukina.codingagent.deepseek;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * DeepSeek Chat Completions 流中的单个增量数据块。
 *
 * @param id 响应 ID
 * @param model 实际模型名称
 * @param choices 候选增量列表
 * @param usage 可选最终 Token 用量
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record DeepSeekChatChunk(
        String id,
        String model,
        List<Choice> choices,
        DeepSeekChatResponse.Usage usage
) {

    /**
     * 单个候选回复增量。
     *
     * @param index 候选索引
     * @param delta 本数据块载荷
     * @param finishReason 可选停止原因
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(
            int index,
            Delta delta,
            @JsonProperty("finish_reason") String finishReason
    ) {
    }

    /**
     * 文本、推理内容和工具调用的增量载荷。
     *
     * @param role 可选消息角色
     * @param content 公开回答文本增量
     * @param reasoningContent 推理文本增量
     * @param toolCalls 工具调用增量列表
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Delta(
            String role,
            String content,
            @JsonProperty("reasoning_content") String reasoningContent,
            @JsonProperty("tool_calls") List<ToolCallDelta> toolCalls
    ) {
    }

    /**
     * 可跨多个数据块拼接的工具调用增量。
     *
     * @param index 工具调用索引
     * @param id 工具调用 ID 增量
     * @param type 工具类型增量
     * @param function 函数名称和参数增量
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ToolCallDelta(
            Integer index,
            String id,
            String type,
            FunctionDelta function
    ) {
    }

    /**
     * 工具名称和 JSON 参数字符串增量。
     *
     * @param name 工具名称增量
     * @param arguments JSON 参数字符串增量
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FunctionDelta(String name, String arguments) {
    }
}
