package com.yukina.codingagent.deepseek;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * DeepSeek Chat Completions 流中的单个增量数据块。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record DeepSeekChatChunk(
        String id,
        String model,
        List<Choice> choices,
        DeepSeekChatResponse.Usage usage
) {

    /** 单个候选回复增量。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(
            int index,
            Delta delta,
            @JsonProperty("finish_reason") String finishReason
    ) {
    }

    /** 文本、推理内容和工具调用的增量载荷。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Delta(
            String role,
            String content,
            @JsonProperty("reasoning_content") String reasoningContent,
            @JsonProperty("tool_calls") List<ToolCallDelta> toolCalls
    ) {
    }

    /** 可跨多个数据块拼接的工具调用增量。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ToolCallDelta(
            Integer index,
            String id,
            String type,
            FunctionDelta function
    ) {
    }

    /** 工具名称和 JSON 参数字符串增量。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FunctionDelta(String name, String arguments) {
    }
}
