package com.yukina.codingagent.agent;

import com.yukina.codingagent.agent.plan.AgentPlan;
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
 * @param plan CODE 运行的最终公开计划；纯聊天时为空
 * @param reflection 结束前反思执行摘要
 * @param processTrace 可持久化的公开工作过程；不包含隐藏推理内容
 */
public record AgentRunResult(
        String answer,
        String model,
        int iterations,
        boolean completed,
        StopReason stopReason,
        List<ToolStep> toolSteps,
        Usage usage,
        AgentPlan plan,
        ReflectionTrace reflection,
        List<ProcessEntry> processTrace
) {

    /**
     * 对工具轨迹做不可变快照，避免返回结果被外部修改。
     */
    public AgentRunResult {
        toolSteps = toolSteps == null ? List.of() : List.copyOf(toolSteps);
        reflection = reflection == null ? new ReflectionTrace(0, 0) : reflection;
        processTrace = processTrace == null ? List.of() : List.copyOf(processTrace);
    }

    /**
     * 创建尚未附加异步运行公开轨迹的完整 Agent 结果。
     *
     * @param answer 最终回答
     * @param model 模型名称
     * @param iterations 模型轮数
     * @param completed 是否完成
     * @param stopReason 停止原因
     * @param toolSteps 工具轨迹
     * @param usage Token 用量
     * @param plan 最终计划
     * @param reflection 结果检查统计
     */
    public AgentRunResult(
            String answer,
            String model,
            int iterations,
            boolean completed,
            StopReason stopReason,
            List<ToolStep> toolSteps,
            Usage usage,
            AgentPlan plan,
            ReflectionTrace reflection
    ) {
        this(answer, model, iterations, completed, stopReason, toolSteps, usage, plan, reflection, List.of());
    }

    /**
     * 创建不包含计划和反思记录的兼容结果。
     *
     * @param answer 最终回答
     * @param model 模型名称
     * @param iterations 模型轮数
     * @param completed 是否完成
     * @param stopReason 停止原因
     * @param toolSteps 工具轨迹
     * @param usage Token 用量
     */
    public AgentRunResult(
            String answer,
            String model,
            int iterations,
            boolean completed,
            StopReason stopReason,
            List<ToolStep> toolSteps,
            Usage usage
    ) {
        this(answer, model, iterations, completed, stopReason, toolSteps, usage, null, null, List.of());
    }

    /**
     * 将异步运行服务收集的公开过程附加到循环结果。
     *
     * @param trace 有界公开过程
     * @return 保留原结果字段并携带过程轨迹的新结果
     */
    public AgentRunResult withProcessTrace(List<ProcessEntry> trace) {
        return new AgentRunResult(
                answer, model, iterations, completed, stopReason, toolSteps, usage, plan, reflection, trace
        );
    }

    /** Agent 循环的停止原因。 */
    public enum StopReason {
        /** 已获得通过约束检查和 Reflection 的最终回答。 */
        COMPLETED,
        /** 模型响应既没有文本也没有有效工具调用。 */
        EMPTY_RESPONSE,
        /** 已达到配置的最大 ReAct 迭代次数。 */
        MAX_ITERATIONS,
        /** 单轮模型请求的工具调用数超过安全边界。 */
        TOOL_CALL_LIMIT,
        /** 模型返回了缺少 ID、函数名或参数的工具调用。 */
        INVALID_TOOL_CALL,
        /** 模型重复了完全相同的确定性失败工具调用。 */
        REPEATED_TOOL_FAILURE,
        /** 模型尝试结束时仍存在可继续执行的计划步骤。 */
        PLAN_INCOMPLETE,
        /** 计划包含经真实失败证据确认的外部阻塞。 */
        PLAN_BLOCKED,
        /** 最终回答流未正常结束，可能只有不完整文本。 */
        RESPONSE_TRUNCATED,
        /** 模型使用非完成型停止原因终止生成。 */
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

    /**
     * Reflection 阶段的公开执行统计。
     *
     * @param rounds 实际执行的审查次数
     * @param revisions 要求返回 ReAct 修正的次数
     */
    public record ReflectionTrace(int rounds, int revisions) {
        /** 校验计数非负且修正次数不超过审查次数。 */
        public ReflectionTrace {
            if (rounds < 0 || revisions < 0 || revisions > rounds) {
                throw new IllegalArgumentException("reflection counts are invalid");
            }
        }
    }

    /**
     * 一条可在主界面展示并持久化的公开 Agent 工作记录。
     *
     * @param id 运行内稳定条目 ID，便于实时事件原地更新
     * @param iteration 所属模型轮次；规划阶段可为零
     * @param type 思考、行动、观察或结果检查
     * @param summary 面向用户的简短摘要
     * @param toolCallId 行动或观察对应的工具调用 ID
     * @param toolName 行动或观察对应的工具名称
     * @param detail 已受轨迹字符上限约束的参数或结果
     * @param success 观察或检查是否成功；运行中及普通条目为空
     */
    public record ProcessEntry(
            String id,
            int iteration,
            ProcessType type,
            String summary,
            String toolCallId,
            String toolName,
            String detail,
            Boolean success
    ) {
        /** 校验公开过程的稳定标识、轮次和类型。 */
        public ProcessEntry {
            if (id == null || id.isBlank() || iteration < 0 || type == null) {
                throw new IllegalArgumentException("process entry fields are invalid");
            }
            summary = summary == null ? "" : summary;
            detail = detail == null ? "" : detail;
        }
    }

    /** 主界面允许展示的公开工作阶段。 */
    public enum ProcessType {
        /** 程序根据公开运行状态生成的思考摘要。 */
        THOUGHT,
        /** 模型发起工具调用的行动。 */
        ACTION,
        /** 工具执行完成后返回的受限结果。 */
        OBSERVATION,
        /** Reflection 或计划证据形成的结果检查。 */
        RESULT_CHECK
    }
}
