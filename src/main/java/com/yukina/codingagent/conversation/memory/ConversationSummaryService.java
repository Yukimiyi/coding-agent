package com.yukina.codingagent.conversation.memory;

import com.yukina.codingagent.agent.ResponseLanguagePolicy;
import com.yukina.codingagent.conversation.model.ConversationMessage;
import com.yukina.codingagent.conversation.model.ConversationSummary;
import com.yukina.codingagent.conversation.repository.ConversationRepository;
import com.yukina.codingagent.conversation.repository.ConversationSummaryRepository;
import com.yukina.codingagent.deepseek.DeepSeekChatResponse;
import com.yukina.codingagent.deepseek.DeepSeekClient;
import com.yukina.codingagent.deepseek.DeepSeekMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 将较早的完整成功对话轮次增量压缩为结构化长期记忆。
 * 摘要失败只记录日志，调用方始终可以退回最近原始消息。
 */
@Service
public class ConversationSummaryService {

    /** 记录摘要失败并支持无异常降级。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(ConversationSummaryService.class);
    /** 每个结构化数组字段允许保存的最大条目数。 */
    private static final int MAX_ITEMS_PER_FIELD = 16;
    /** 单个结构化摘要条目的最大字符数。 */
    private static final int MAX_ITEM_CHARS = 500;
    /** 总体目标字段的最大字符数。 */
    private static final int MAX_GOAL_CHARS = 1000;

    /** 查询摘要水位之后成功消息的仓储。 */
    private final ConversationRepository conversationRepository;
    /** 保存结构化摘要及其消息水位的仓储。 */
    private final ConversationSummaryRepository summaryRepository;
    /** 最近原始轮次窗口配置。 */
    private final ConversationContextProperties contextProperties;
    /** 摘要触发阈值、输入和输出预算。 */
    private final ConversationSummaryProperties properties;
    /** 执行无工具摘要模型调用的客户端。 */
    private final DeepSeekClient deepSeekClient;
    /** 解析和规范化结构化摘要 JSON 的映射器。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建增量滚动摘要服务。
     *
     * @param conversationRepository 成功消息仓储
     * @param summaryRepository 滚动摘要仓储
     * @param contextProperties 最近原始消息窗口
     * @param properties 摘要阈值与预算
     * @param deepSeekClient 无工具摘要模型客户端
     * @param objectMapper JSON 解析器
     */
    public ConversationSummaryService(
            ConversationRepository conversationRepository,
            ConversationSummaryRepository summaryRepository,
            ConversationContextProperties contextProperties,
            ConversationSummaryProperties properties,
            DeepSeekClient deepSeekClient,
            ObjectMapper objectMapper
    ) {
        this.conversationRepository = conversationRepository;
        this.summaryRepository = summaryRepository;
        this.contextProperties = contextProperties;
        this.properties = properties;
        this.deepSeekClient = deepSeekClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 达到消息数或字符阈值时压缩可淘汰的完整 Turn。
     *
     * @param conversationId 会话 ID
     * @return 本次成功更新摘要时返回 {@code true}
     */
    public boolean compactIfNeeded(String conversationId) {
        if (!properties.enabled()) {
            return false;
        }
        try {
            ConversationSummary previous = summaryRepository.find(conversationId).orElse(null);
            long afterId = previous == null ? 0 : previous.lastMessageId();
            List<ConversationMessage> unsummarized = conversationRepository.findSuccessfulMessagesAfter(
                    conversationId,
                    afterId,
                    properties.maxBatchMessages()
            );
            int totalChars = unsummarized.stream().mapToInt(message -> message.content().length()).sum();
            if (unsummarized.size() < properties.triggerMessages()
                    && totalChars < properties.triggerContentChars()) {
                return false;
            }

            List<Turn> turns = completeTurns(unsummarized);
            int compactCount = compactTurnCount(turns);
            if (compactCount <= 0) {
                return false;
            }
            List<Turn> compactedTurns = turns.subList(0, compactCount);
            String latestTask = turns.getLast().user().content();
            String context = buildContext(previous, compactedTurns);
            DeepSeekChatResponse response = deepSeekClient.chat(
                    List.of(
                            DeepSeekMessage.system(
                                    properties.systemPrompt()
                                            + "\n\n"
                                            + ResponseLanguagePolicy.instructionFor(latestTask)
                            ),
                            DeepSeekMessage.user(context)
                    ),
                    List.of()
            );
            String normalized = normalizeSummary(response.firstContent());
            long lastMessageId = compactedTurns.getLast().assistant().id();
            summaryRepository.upsert(conversationId, normalized, lastMessageId, Instant.now());
            return true;
        } catch (RuntimeException exception) {
            LOGGER.warn("Unable to compact conversation summary for {}", conversationId, exception);
            return false;
        }
    }

    /**
     * 计算在保留最近原始消息窗口后可以压缩的完整轮次数。
     *
     * @param turns 摘要水位之后的完整成功轮次
     * @return 本次可以压缩的前缀轮次数
     */
    private int compactTurnCount(List<Turn> turns) {
        if (turns.size() <= 1) {
            return 0;
        }
        int maxRecentTurns = Math.max(1, contextProperties.maxMessages() / 2);
        int compactCount = Math.max(0, turns.size() - maxRecentTurns);
        int retainedChars = turns.subList(compactCount, turns.size()).stream().mapToInt(Turn::contentChars).sum();
        while (retainedChars >= properties.triggerContentChars() && compactCount < turns.size() - 1) {
            retainedChars -= turns.get(compactCount).contentChars();
            compactCount++;
        }
        return compactCount;
    }

    /**
     * 从成功消息序列中提取 USER 后紧随 ASSISTANT 的完整轮次。
     *
     * @param messages 按消息 ID 正序排列的成功消息
     * @return 不包含孤立消息的完整轮次
     */
    private static List<Turn> completeTurns(List<ConversationMessage> messages) {
        List<Turn> turns = new ArrayList<>();
        ConversationMessage pendingUser = null;
        for (ConversationMessage message : messages) {
            if (message.role() == ConversationMessage.Role.USER) {
                pendingUser = message;
            } else if (pendingUser != null) {
                turns.add(new Turn(pendingUser, message));
                pendingUser = null;
            }
        }
        return List.copyOf(turns);
    }

    /**
     * 构建包含旧摘要和本批完整 Turn 的受限模型输入。
     * 每条消息至少保留一个片段，避免输入整体截断后仍错误推进摘要水位。
     *
     * @param previous 已持久化的上一版摘要；首次压缩时为空
     * @param turns 本次需要合并进摘要的完整轮次
     * @return 不超过摘要上下文预算且覆盖每个待压缩轮次的文本
     */
    private String buildContext(ConversationSummary previous, List<Turn> turns) {
        StringBuilder context = new StringBuilder();
        context.append("## PREVIOUS MEMORY\n");
        String previousMemory = previous == null ? "{}" : previous.summary();
        int previousBudget = Math.min(
                properties.maxSummaryChars(),
                Math.max(256, properties.maxContextChars() / 3)
        );
        context.append(abbreviate(previousMemory, previousBudget));
        context.append("\n## NEW COMPLETED TURNS\n");

        int remainingMessages = turns.size() * 2;
        for (Turn turn : turns) {
            appendMessageFragment(context, "USER", turn.user(), remainingMessages--);
            appendMessageFragment(context, "ASSISTANT", turn.assistant(), remainingMessages--);
        }
        return context.length() <= properties.maxContextChars()
                ? context.toString()
                : context.substring(0, properties.maxContextChars());
    }

    /**
     * 在剩余消息间均分当前字符预算，并追加一条带稳定 ID 的消息片段。
     *
     * @param context 摘要模型输入缓冲区
     * @param role USER 或 ASSISTANT 展示角色
     * @param message 待追加的持久化消息
     * @param remainingMessages 包含当前消息在内的剩余消息数
     */
    private void appendMessageFragment(
            StringBuilder context,
            String role,
            ConversationMessage message,
            int remainingMessages
    ) {
        String header = role + " [messageId=" + message.id() + "]:\n";
        int available = Math.max(0, properties.maxContextChars() - context.length());
        int share = Math.max(0, available / Math.max(1, remainingMessages));
        int contentBudget = Math.max(0, share - header.length() - 1);
        context.append(header);
        context.append(abbreviate(message.content(), contentBudget));
        context.append('\n');
    }

    /**
     * 按字符预算缩短单条摘要输入消息。
     *
     * @param value 原始消息正文
     * @param maxChars 最大保留字符数
     * @return 带显式截断标记且不超过预算的消息片段
     */
    private static String abbreviate(String value, int maxChars) {
        if (value == null || maxChars <= 0) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        String marker = "\n[message truncated]";
        if (maxChars <= marker.length()) {
            return value.substring(0, maxChars);
        }
        return value.substring(0, maxChars - marker.length()) + marker;
    }

    /**
     * 解析模型摘要并约束字段数量、单项长度和总字符数。
     *
     * @param content 摘要模型原始响应
     * @return 可持久化的规范化 JSON 摘要
     */
    private String normalizeSummary(String content) {
        try {
            JsonNode root = objectMapper.readTree(stripCodeFence(content));
            MemoryPayload payload = new MemoryPayload(
                    limit(root.path("goal").asText(), MAX_GOAL_CHARS),
                    stringList(root.path("constraints")),
                    stringList(root.path("decisions")),
                    stringList(root.path("completed")),
                    stringList(root.path("openIssues")),
                    stringList(root.path("references"))
            );
            return fitSummary(payload);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalStateException("Summary model returned invalid structured memory", exception);
        }
    }

    /**
     * 逐步删除最大数组尾部条目，直到摘要满足字符预算。
     *
     * @param payload 已完成字段级规范化的结构化记忆
     * @return 满足持久化字符预算的 JSON
     * @throws JacksonException 摘要无法序列化时抛出
     */
    private String fitSummary(MemoryPayload payload) throws JacksonException {
        List<List<String>> fields = List.of(
                payload.constraints(),
                payload.decisions(),
                payload.completed(),
                payload.openIssues(),
                payload.references()
        );
        String serialized = objectMapper.writeValueAsString(payload);
        while (serialized.length() > properties.maxSummaryChars()) {
            List<String> largest = fields.stream()
                    .filter(field -> !field.isEmpty())
                    .max(Comparator.comparingInt(List::size))
                    .orElse(null);
            if (largest == null) {
                throw new IllegalArgumentException("summary cannot fit configured character limit");
            }
            largest.removeLast();
            serialized = objectMapper.writeValueAsString(payload);
        }
        return serialized;
    }

    /**
     * 从 JSON 数组提取经过数量和单项长度约束的非空字符串。
     *
     * @param node 待解析 JSON 数组节点
     * @return 规范化字符串列表；非数组返回空列表
     */
    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (values.size() >= MAX_ITEMS_PER_FIELD) {
                    break;
                }
                String value = limit(item.asText(), MAX_ITEM_CHARS);
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    /**
     * 去除摘要模型偶发添加的 Markdown JSON 围栏。
     *
     * @param content 摘要模型原始响应
     * @return 可交给 JSON 解析器的文本
     */
    private static String stripCodeFence(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("summary response must not be blank");
        }
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstLineEnd = trimmed.indexOf('\n');
        int closingFence = trimmed.lastIndexOf("```");
        return firstLineEnd >= 0 && closingFence > firstLineEnd
                ? trimmed.substring(firstLineEnd + 1, closingFence).trim()
                : trimmed;
    }

    /**
     * 去除首尾空白并限制字符串长度。
     *
     * @param value 待规范化文本
     * @param maxChars 最大字符数
     * @return 不超过指定字符数的文本
     */
    private static String limit(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars);
    }

    /**
     * 一个完整的成功用户与助手对话轮次。
     *
     * @param user 本轮成功用户消息
     * @param assistant 紧随用户消息的成功助手回答
     */
    private record Turn(ConversationMessage user, ConversationMessage assistant) {
        /**
         * 计算本轮原始文本字符数。
         *
         * @return 用户与助手正文字符数之和
         */
        private int contentChars() {
            return user.content().length() + assistant.content().length();
        }
    }

    /**
     * 模型和数据库共享的结构化长期记忆格式。
     *
     * @param goal 当前会话的总体目标
     * @param constraints 用户明确提出且仍有效的约束
     * @param decisions 已确认的技术或产品选择
     * @param completed 有成功证据支持的完成事项
     * @param openIssues 尚未解决的问题
     * @param references 有后续价值的文件路径或命令
     */
    private record MemoryPayload(
            String goal,
            List<String> constraints,
            List<String> decisions,
            List<String> completed,
            List<String> openIssues,
            List<String> references
    ) {
    }
}
