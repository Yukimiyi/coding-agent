package com.yukina.codingagent.agent.reflection;

import java.util.List;

/**
 * 一次反思审查的结构化结论。
 *
 * @param verdict PASS 表示可以结束，REVISE 表示需要回到 ReAct 循环
 * @param summary 可安全展示给用户的简短结论
 * @param issues 需要修正的具体问题；PASS 时通常为空
 */
public record ReflectionFeedback(
        Verdict verdict,
        String summary,
        List<String> issues
) {

    /**
     * 规范化审查结论并复制问题列表。
     *
     * @throws IllegalArgumentException verdict 为空时抛出
     */
    public ReflectionFeedback {
        if (verdict == null) {
            throw new IllegalArgumentException("reflection verdict must not be null");
        }
        summary = summary == null || summary.isBlank() ? defaultSummary(verdict) : summary.trim();
        issues = issues == null
                ? List.of()
                : issues.stream().filter(issue -> issue != null && !issue.isBlank()).map(String::trim).toList();
    }

    /**
     * 判断是否需要把反馈送回 Agent 继续修改。
     *
     * @return 结论为 REVISE 时返回 {@code true}
     */
    public boolean requiresRevision() {
        return verdict == Verdict.REVISE;
    }

    /**
     * 生成供界面展示的反思摘要，不包含模型隐藏推理内容。
     *
     * @return 形如“PASS · 验证证据充分”的公开文本
     */
    public String publicSummary() {
        return verdict + " · " + summary;
    }

    /**
     * 将 REVISE 结论转换为下一轮 ReAct 的内部任务反馈。
     *
     * @return 要求重新检查、行动和验证的用户角色消息
     * @throws IllegalStateException PASS 结论不应被送回循环时抛出
     */
    public String revisionInstruction() {
        return revisionInstruction(false);
    }

    /**
     * 将 REVISE 结论转换为指定语言的下一轮 ReAct 反馈。
     *
     * @param chinese 是否使用简体中文内部指令
     * @return 要求重新检查、行动和验证的用户角色消息
     */
    public String revisionInstruction(boolean chinese) {
        if (!requiresRevision()) {
            throw new IllegalStateException("PASS feedback does not require another Agent iteration");
        }
        StringBuilder instruction = chinese
                ? new StringBuilder("最终回答检查发现了可修正的问题。请重新进入 ReAct 循环：检查证据，按需使用工具"
                        + "修正项目，验证结果，然后用中文给出新的最终回答。\n检查摘要：" + summary)
                : new StringBuilder(
                        "A final-answer review found actionable issues. Re-enter the ReAct loop: inspect the evidence, "
                                + "use tools to correct the project when needed, verify the result, and then provide a new final answer.\n"
                                + "Review summary: " + summary
                );
        if (!issues.isEmpty()) {
            instruction.append(chinese ? "\n需要处理的问题：" : "\nIssues to address:");
            for (String issue : issues) {
                instruction.append("\n- ").append(issue);
            }
        }
        return instruction.toString();
    }

    /**
     * 生成对应结论的默认公开摘要。
     *
     * @param verdict 反思结论
     * @return PASS 或 REVISE 对应的简短中文说明
     */
    private static String defaultSummary(Verdict verdict) {
        return verdict == Verdict.PASS ? "当前实现可以结束" : "当前实现仍需修正";
    }

    /** 审查模型允许返回的两种状态。 */
    public enum Verdict {
        /** 当前结果满足验收条件，可以生成最终回答。 */
        PASS,
        /** 当前结果存在可修正问题，需要返回 ReAct 循环。 */
        REVISE
    }
}
