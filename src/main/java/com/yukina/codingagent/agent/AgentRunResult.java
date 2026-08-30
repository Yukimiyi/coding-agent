package com.yukina.codingagent.agent;

import com.yukina.codingagent.tool.ToolExecutionResult;

import java.util.List;

/**
 * 一次 Agent 执行的完整结果，包含最终回答、停止原因和工具轨迹。
 *
 * @param answer 最终回答；未正常完成时可能为空
 * @param model 实际响应的模型名称
 * @param iterations 已执行的模型调用轮数
 * @param completed 是否得到有效最终回答
 * @param stopReason 循环停止原因
 * @param toolSteps 工具调用轨迹
 * @param usage 累计 Token 用量
 */
public record AgentRunResult(
        String answer,
        String model,
        int iterations,
        boolean completed,
        StopReason stopReason,
        List<ToolStep> toolSteps,
        Usage usage
) {

    /**
     * 对工具轨迹做不可变快照，避免返回结果被外部修改。
     */
    public AgentRunResult {
        toolSteps = toolSteps == null ? List.of() : List.copyOf(toolSteps);
    }

    /** Agent 循环的停止原因。 */
    public enum StopReason {
        COMPLETED,
        EMPTY_RESPONSE,
        MAX_ITERATIONS,
        TOOL_CALL_LIMIT,
        INVALID_TOOL_CALL,
        REPEATED_TOOL_FAILURE,
        RESPONSE_TRUNCATED,
        MODEL_STOPPED
    }

    /**
     * 一次工具调用在执行轨迹中的摘要。
     *
     * @param iteration 发生调用的循环轮次
     * @param toolCallId 模型生成的工具调用 ID
     * @param toolName 工具名称
     * @param arguments 工具参数摘要
     * @param argumentsTruncated 参数是否被截断
     * @param success 工具是否执行成功
     * @param content 工具结果摘要
     * @param contentTruncated 结果是否被截断
     * @param error 结构化错误；成功时为空
     */
    public record ToolStep(
            int iteration,
            String toolCallId,
            String toolName,
            String arguments,
            boolean argumentsTruncated,
            boolean success,
            String content,
            boolean contentTruncated,
            ToolExecutionResult.Error error
    ) {
    }

    /**
     * 多轮模型调用累计的 Token 用量。
     *
     * @param promptTokens 输入 Token 数
     * @param completionTokens 输出 Token 数
     * @param totalTokens 总 Token 数
     */
    public record Usage(long promptTokens, long completionTokens, long totalTokens) {
    }
}
