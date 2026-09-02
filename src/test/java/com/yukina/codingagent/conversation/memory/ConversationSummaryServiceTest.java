package com.yukina.codingagent.conversation.memory;

import com.yukina.codingagent.conversation.model.ConversationMessage;
import com.yukina.codingagent.conversation.model.ConversationSummary;
import com.yukina.codingagent.conversation.repository.ConversationRepository;
import com.yukina.codingagent.conversation.repository.ConversationSummaryRepository;
import com.yukina.codingagent.deepseek.DeepSeekChatResponse;
import com.yukina.codingagent.deepseek.DeepSeekClient;
import com.yukina.codingagent.deepseek.DeepSeekMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证增量滚动摘要、最近轮次保留和失败降级。 */
class ConversationSummaryServiceTest {

    private EmbeddedDatabase database;
    private ConversationRepository conversationRepository;
    private ConversationSummaryRepository summaryRepository;
    private ConversationContextProperties contextProperties;
    private ConversationSummaryProperties summaryProperties;
    private DeepSeekClient deepSeekClient;
    private ConversationSummaryService summaryService;

    /** 创建隔离会话数据库和低阈值摘要配置。 */
    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .addScript("schema.sql")
                .build();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(database);
        conversationRepository = new ConversationRepository(jdbcTemplate);
        summaryRepository = new ConversationSummaryRepository(jdbcTemplate);
        conversationRepository.create("conversation-1", "Test", Instant.now());
        contextProperties = new ConversationContextProperties(
                10,
                1000,
                10000,
                Duration.ofMinutes(30),
                10
        );
        summaryProperties = new ConversationSummaryProperties(
                true,
                16,
                10000,
                12000,
                6000,
                100,
                "Return structured conversation memory as JSON."
        );
        deepSeekClient = mock(DeepSeekClient.class);
        summaryService = new ConversationSummaryService(
                conversationRepository,
                summaryRepository,
                contextProperties,
                summaryProperties,
                deepSeekClient,
                new ObjectMapper()
        );
    }

    /** 关闭当前用例数据库。 */
    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    /** 验证较早四轮被摘要，而最近五轮仍以原文进入下一次模型上下文。 */
    @Test
    void compactsOldTurnsAndKeepsTenRecentRawMessages() {
        appendSuccessfulTurns(9);
        when(deepSeekClient.chat(anyList(), anyList())).thenReturn(summaryResponse(
                "{\"goal\":\"持续完成编码任务\",\"constraints\":[\"使用中文\"],"
                        + "\"decisions\":[],\"completed\":[\"前四轮已完成\"],"
                        + "\"openIssues\":[],\"references\":[]}"
        ));

        assertTrue(summaryService.compactIfNeeded("conversation-1"));

        ConversationSummary summary = summaryRepository.find("conversation-1").orElseThrow();
        assertEquals(8, summary.lastMessageId());
        assertTrue(summary.summary().contains("持续完成编码任务"));
        ConversationContextManager contextManager = new ConversationContextManager(
                conversationRepository,
                summaryRepository,
                new InMemoryConversationMemoryStore(contextProperties),
                contextProperties
        );
        List<DeepSeekMessage> context = contextManager.load("conversation-1");
        assertEquals(11, context.size());
        assertEquals("system", context.getFirst().role());
        assertTrue(context.getFirst().content().contains("持续完成编码任务"));
        assertEquals("user-5", context.get(1).content());
        assertEquals("assistant-9", context.getLast().content());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeepSeekMessage>> request = ArgumentCaptor.forClass(List.class);
        verify(deepSeekClient).chat(request.capture(), anyList());
        assertTrue(request.getValue().getLast().content().contains("user-1"));
        assertTrue(request.getValue().getLast().content().contains("assistant-4"));
    }

    /** 验证摘要模型异常不会中断上下文加载，也不会推进摘要水位。 */
    @Test
    void fallsBackToRecentRawMessagesWhenSummarizationFails() {
        appendSuccessfulTurns(9);
        when(deepSeekClient.chat(anyList(), anyList()))
                .thenThrow(new IllegalStateException("summary model unavailable"));

        assertFalse(summaryService.compactIfNeeded("conversation-1"));
        assertTrue(summaryRepository.find("conversation-1").isEmpty());

        ConversationContextManager contextManager = new ConversationContextManager(
                conversationRepository,
                summaryRepository,
                new InMemoryConversationMemoryStore(contextProperties),
                contextProperties
        );
        List<DeepSeekMessage> context = contextManager.load("conversation-1");
        assertEquals(10, context.size());
        assertEquals("user-5", context.getFirst().content());
        assertEquals("assistant-9", context.getLast().content());
    }

    /** 按 USER、ASSISTANT 顺序写入指定数量的成功轮次。 */
    private void appendSuccessfulTurns(int count) {
        for (int turn = 1; turn <= count; turn++) {
            conversationRepository.appendMessage(
                    "conversation-1",
                    ConversationMessage.Role.USER,
                    "user-" + turn,
                    ConversationMessage.Status.SUCCESS,
                    Instant.now()
            );
            conversationRepository.appendMessage(
                    "conversation-1",
                    ConversationMessage.Role.ASSISTANT,
                    "assistant-" + turn,
                    ConversationMessage.Status.SUCCESS,
                    Instant.now()
            );
        }
    }

    /** 创建只包含公开 JSON 文本的摘要模型响应。 */
    private static DeepSeekChatResponse summaryResponse(String content) {
        return new DeepSeekChatResponse(
                "summary-response",
                "deepseek-test",
                List.of(new DeepSeekChatResponse.Choice(
                        0,
                        DeepSeekMessage.assistant(content, null, null),
                        "stop"
                )),
                new DeepSeekChatResponse.Usage(1, 1, 2)
        );
    }
}
