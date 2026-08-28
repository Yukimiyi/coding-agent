package com.yukina.codingagent.conversation.service;

import com.yukina.codingagent.agent.AgentLoop;
import com.yukina.codingagent.agent.AgentRunResult;
import com.yukina.codingagent.conversation.memory.ConversationContextManager;
import com.yukina.codingagent.conversation.memory.ConversationContextProperties;
import com.yukina.codingagent.conversation.memory.InMemoryConversationMemoryStore;
import com.yukina.codingagent.conversation.model.ConversationChatResult;
import com.yukina.codingagent.conversation.model.MessagePage;
import com.yukina.codingagent.conversation.repository.ConversationRepository;
import com.yukina.codingagent.deepseek.DeepSeekMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证跨请求的有状态 Agent 对话编排。 */
class ConversationAgentServiceTest {

    private EmbeddedDatabase database;
    private ConversationService conversationService;
    private ConversationAgentService conversationAgentService;
    private AgentLoop agentLoop;

    /** 创建真实会话组件和模拟 Agent 循环。 */
    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .addScript("schema.sql")
                .build();
        ConversationRepository repository = new ConversationRepository(new JdbcTemplate(database));
        ConversationContextProperties properties = new ConversationContextProperties(
                20,
                1000,
                Duration.ofMinutes(30),
                100
        );
        ConversationContextManager contextManager = new ConversationContextManager(
                repository,
                new InMemoryConversationMemoryStore(properties),
                properties
        );
        conversationService = new ConversationService(repository, contextManager);
        agentLoop = mock(AgentLoop.class);
        conversationAgentService = new ConversationAgentService(
                agentLoop,
                conversationService,
                contextManager,
                new ConversationLockManager()
        );
    }

    /** 关闭当前用例的嵌入式数据库。 */
    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    /** 验证后续请求获得前一轮历史并持久化完整消息。 */
    @Test
    void preservesHistoryAcrossConversationRequests() {
        when(agentLoop.run(eq("First question"), anyList())).thenReturn(completed("First answer"));
        when(agentLoop.run(eq("Follow-up"), anyList())).thenReturn(completed("Second answer"));

        ConversationChatResult first = conversationAgentService.chat(null, "First question");
        ConversationChatResult second = conversationAgentService.chat(first.conversationId(), "Follow-up");

        assertTrue(first.conversationCreated());
        assertFalse(second.conversationCreated());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeepSeekMessage>> history = ArgumentCaptor.forClass(List.class);
        verify(agentLoop).run(eq("Follow-up"), history.capture());
        assertEquals(List.of("user", "assistant"), history.getValue().stream()
                .map(DeepSeekMessage::role)
                .toList());
        assertEquals(List.of("First question", "First answer"), history.getValue().stream()
                .map(DeepSeekMessage::content)
                .toList());

        MessagePage page = conversationService.messages(first.conversationId(), null, 10);
        assertEquals(4, page.messages().size());
        assertEquals("Second answer", page.messages().getLast().content());
    }

    /** 创建已成功完成的 Agent 测试结果。 */
    private static AgentRunResult completed(String answer) {
        return new AgentRunResult(
                answer,
                "deepseek-test",
                1,
                true,
                AgentRunResult.StopReason.COMPLETED,
                List.of(),
                new AgentRunResult.Usage(1, 1, 2)
        );
    }
}
