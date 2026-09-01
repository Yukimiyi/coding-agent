package com.yukina.codingagent.agent.run;

import com.yukina.codingagent.agent.AgentRunResult;

import java.time.Instant;

/**
 * 一条可重放的运行事件，不包含模型隐藏推理内容。
 *
 * @param sequence 运行内单调递增事件序号
 * @param runId 运行 ID
 * @param timestamp 事件生成时间
 * @param type 公开事件类型
 * @param status 事件产生时的运行状态
 * @param iteration 可选模型轮次
 * @param model 可选模型名称
 * @param toolCallCount 可选工具调用数量
 * @param toolCallId 可选工具调用 ID
 * @param toolName 可选工具名称
 * @param arguments 可选且受限的工具参数
 * @param toolStep 可选完整工具轨迹项
 * @param result 可选最终 Agent 结果
 * @param message 可选公开文本或错误说明
 */
public record AgentRunEvent(
        long sequence,
        String runId,
        Instant timestamp,
        AgentRunEventType type,
        AgentRunStatus status,
        Integer iteration,
        String model,
        Integer toolCallCount,
        String toolCallId,
        String toolName,
        String arguments,
        AgentRunResult.ToolStep toolStep,
        AgentRunResult result,
        String message
) {
}
