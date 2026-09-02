package com.yukina.codingagent.agent.reflection;

import com.yukina.codingagent.agent.AgentRunResult;
import com.yukina.codingagent.agent.ResponseLanguagePolicy;
import com.yukina.codingagent.agent.plan.AgentPlan;
import com.yukina.codingagent.deepseek.DeepSeekChatResponse;
import com.yukina.codingagent.deepseek.DeepSeekClient;
import com.yukina.codingagent.deepseek.DeepSeekMessage;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 使用不带工具定义的 DeepSeek 调用实现结束前反思审查。
 */
@Service
public class DeepSeekReflectionReviewer implements ReflectionReviewer {

    /** 能够形成文件变更证据的工具名称集合。 */
    private static final Set<String> MUTATING_TOOLS = Set.of("write_file", "edit_file", "delete_file");
    /** 能够形成编译、测试或运行验证证据的命令工具名称。 */
    private static final String EXECUTE_COMMAND = "execute_command";

    /** 执行无工具审查调用的 DeepSeek 客户端。 */
    private final DeepSeekClient deepSeekClient;
    /** 解析审查模型 JSON 及工具参数的映射器。 */
    private final ObjectMapper objectMapper;
    /** Reflection 输入预算、轮数和系统提示词配置。 */
    private final ReflectionProperties properties;

    /**
     * 创建无工具反思审查器。
     *
     * @param deepSeekClient DeepSeek 模型客户端
     * @param objectMapper JSON 解析器
     * @param properties 反思次数、上下文和提示词配置
     */
    public DeepSeekReflectionReviewer(
            DeepSeekClient deepSeekClient,
            ObjectMapper objectMapper,
            ReflectionProperties properties
    ) {
        this.deepSeekClient = deepSeekClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** {@inheritDoc} */
    @Override
    public ReflectionReview review(
            String task,
            String candidateAnswer,
            List<AgentRunResult.ToolStep> toolSteps,
            AgentPlan plan
    ) {
        String evidence = buildEvidence(task, candidateAnswer, toolSteps == null ? List.of() : toolSteps, plan);
        DeepSeekChatResponse response = deepSeekClient.chat(
                List.of(
                        DeepSeekMessage.system(
                                properties.systemPrompt() + "\n\n" + ResponseLanguagePolicy.instructionFor(task)
                        ),
                        DeepSeekMessage.user(evidence)
                ),
                List.of()
        );
        return new ReflectionReview(parseFeedback(response.firstContent(), task), response.usage());
    }

    /**
     * 只汇总可验证的公开证据，不把原始隐藏思维链传给审查模型。
     *
     * @param task 原始任务
     * @param candidateAnswer 候选回答
     * @param toolSteps 受限工具轨迹
     * @param plan 可选最终计划状态
     * @return 按配置上限截断的审查证据
     */
    private String buildEvidence(
            String task,
            String candidateAnswer,
            List<AgentRunResult.ToolStep> toolSteps,
            AgentPlan plan
    ) {
        EvidenceBuilder evidence = new EvidenceBuilder(properties.maxContextChars());
        evidence.section("ORIGINAL TASK", task);
        evidence.section("CANDIDATE FINAL ANSWER", candidateAnswer);
        if (plan != null) {
            evidence.section("FINAL EXECUTION PLAN", plan.toPrompt());
        }

        List<String> changes = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<String> verification = new ArrayList<>();
        for (AgentRunResult.ToolStep step : toolSteps) {
            if (step.success() && MUTATING_TOOLS.contains(step.toolName())) {
                changes.add(step.toolName() + " path=" + argumentPath(step.arguments()));
            }
            if (!step.success()) {
                String error = step.error() == null
                        ? "unknown error"
                        : step.error().code() + ": " + step.error().message();
                failures.add(step.toolName() + " args=" + step.arguments() + " result=" + error);
            }
            if (EXECUTE_COMMAND.equals(step.toolName())) {
                verification.add("args=" + step.arguments() + " result=" + step.content());
            }
        }
        evidence.lines("SUCCESSFUL FILE CHANGES", changes, "none");
        evidence.lines("TOOL FAILURES", failures, "none");
        evidence.lines("VERIFICATION COMMANDS", verification, "none");
        evidence.section(
                "OUTPUT CONTRACT",
                "Return JSON only: {\"verdict\":\"PASS|REVISE\",\"summary\":\"short public summary\","
                        + "\"issues\":[\"specific actionable issue\"]}. PASS only when the available evidence supports "
                        + "the requested result and there is no actionable issue. Do not request changes merely because "
                        + "evidence omitted by this bounded trace is unknown."
        );
        return evidence.value();
    }

    /**
     * 从工具参数中尽力提取工作区相对路径。
     *
     * @param arguments 工具 JSON 参数
     * @return path 字段；无法解析时返回 unknown
     */
    private String argumentPath(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "unknown";
        }
        try {
            String path = objectMapper.readTree(arguments).path("path").asText();
            return path == null || path.isBlank() ? "unknown" : path;
        } catch (JacksonException exception) {
            return "unknown";
        }
    }

    /**
     * 解析审查模型的 JSON，并拒绝协议外状态。
     *
     * @param content 模型文本
     * @param task 原始用户任务，用于保证公开摘要语言一致
     * @return 结构化 PASS 或 REVISE 反馈
     * @throws IllegalStateException 文本不是要求的 JSON 协议时抛出
     */
    private ReflectionFeedback parseFeedback(String content, String task) {
        try {
            JsonNode root = objectMapper.readTree(stripCodeFence(content));
            ReflectionFeedback.Verdict verdict = ReflectionFeedback.Verdict.valueOf(
                    root.path("verdict").asText().trim().toUpperCase(Locale.ROOT)
            );
            List<String> issues = new ArrayList<>();
            JsonNode issueNodes = root.path("issues");
            if (issueNodes.isArray()) {
                issueNodes.forEach(node -> issues.add(node.asText()));
            }
            String summary = root.path("summary").asText();
            if (ResponseLanguagePolicy.requiresChineseRewrite(task, summary)) {
                summary = verdict == ReflectionFeedback.Verdict.PASS
                        ? "现有证据支持任务完成"
                        : "结果检查发现仍有需要修正的问题";
            }
            return new ReflectionFeedback(verdict, summary, issues);
        } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
            throw new IllegalStateException("Reflection model returned an invalid PASS/REVISE response", exception);
        }
    }

    /**
     * 去除审查模型偶发添加的 Markdown JSON 围栏。
     *
     * @param content 审查模型原始响应
     * @return 可交给 JSON 解析器的响应文本
     */
    private static String stripCodeFence(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("Reflection model returned an empty response");
        }
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstLineEnd = trimmed.indexOf('\n');
        int closingFence = trimmed.lastIndexOf("```");
        if (firstLineEnd < 0 || closingFence <= firstLineEnd) {
            return trimmed;
        }
        return trimmed.substring(firstLineEnd + 1, closingFence).trim();
    }

    /** 按总字符预算拼接清晰的证据分区。 */
    private static final class EvidenceBuilder {
        /** 最终证据文本最大字符数。 */
        private final int limit;
        /** 已按预算累计的证据文本。 */
        private final StringBuilder value = new StringBuilder();

        /**
         * 创建空的有界证据构建器。
         *
         * @param limit 最终证据最大字符数
         */
        private EvidenceBuilder(int limit) {
            this.limit = limit;
        }

        /**
         * 追加一个带 Markdown 标题的证据分区。
         *
         * @param title 分区标题
         * @param content 分区内容
         */
        private void section(String title, String content) {
            append("\n## " + title + "\n" + (content == null ? "" : content) + "\n");
        }

        /**
         * 将字符串列表转换为项目符号证据分区。
         *
         * @param title 分区标题
         * @param lines 证据条目
         * @param emptyValue 空列表替代文本
         */
        private void lines(String title, List<String> lines, String emptyValue) {
            section(title, lines.isEmpty() ? emptyValue : "- " + String.join("\n- ", lines));
        }

        /**
         * 在剩余字符预算内追加文本。
         *
         * @param text 待加入字符预算的文本
         */
        private void append(String text) {
            int remaining = limit - value.length();
            if (remaining <= 0) {
                return;
            }
            value.append(text, 0, Math.min(remaining, text.length()));
        }

        /**
         * 返回当前证据文本。
         *
         * @return 已按总预算截断的证据文本
         */
        private String value() {
            return value.toString();
        }
    }
}
