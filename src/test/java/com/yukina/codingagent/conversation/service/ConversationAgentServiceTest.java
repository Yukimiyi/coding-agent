package com.yukina.codingagent.conversation.service;

import com.yukina.codingagent.agent.AgentLoop;
import com.yukina.codingagent.agent.AgentRunResult;
import com.yukina.codingagent.conversation.memory.ConversationContextManager;
import com.yukina.codingagent.conversation.memory.ConversationContextProperties;
import com.yukina.codingagent.conversation.memory.InMemoryConversationMemoryStore;
import com.yukina.codingagent.conversation.model.ConversationChatResult;
import com.yukina.codingagent.conversation.model.ConversationMessage;
import com.yukina.codingagent.conversation.model.ConversationMode;
import com.yukina.codingagent.conversation.model.MessagePage;
import com.yukina.codingagent.conversation.repository.ConversationRepository;
import com.yukina.codingagent.conversation.workspace.ConversationWorkspaceProperties;
import com.yukina.codingagent.conversation.workspace.ConversationWorkspaceService;
import com.yukina.codingagent.deepseek.DeepSeekMessage;
import com.yukina.codingagent.tool.workspace.WorkspaceExecutionContext;
import com.yukina.codingagent.tool.workspace.WorkspaceProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Duration;
import java.util.List;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证跨请求的有状态 Agent 对话编排。 */
class ConversationAgentServiceTest {

    @TempDir
    Path workspaceRoot;

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
        JdbcTemplate jdbcTemplate = new JdbcTemplate(database);
        ConversationRepository repository = new ConversationRepository(jdbcTemplate);
        ConversationContextProperties properties = new ConversationContextProperties(
                20,
                1000,
                4000,
                Duration.ofMinutes(30),
                100
        );
        ConversationContextManager contextManager = new ConversationContextManager(
                repository,
                new InMemoryConversationMemoryStore(properties),
                properties
        );
        ConversationWorkspaceService workspaceService = new ConversationWorkspaceService(
                new ConversationWorkspaceProperties(workspaceRoot, 20, 1024, 4096)
        );
        workspaceService.initialize();
        conversationService = new ConversationService(repository, contextManager, workspaceService);
        agentLoop = mock(AgentLoop.class);
        WorkspaceProperties workspaceProperties = new WorkspaceProperties(
                workspaceRoot, 1024, 1024, 100, 50, 1024, 5
        );
        conversationAgentService = new ConversationAgentService(
                agentLoop,
                conversationService,
                contextManager,
                new ConversationLockManager(),
                workspaceService,
                new WorkspaceExecutionContext(workspaceProperties)
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
        when(agentLoop.run(eq("First question"), anyList(), any(), any())).thenReturn(completed("First answer"));
        when(agentLoop.run(eq("Follow-up"), anyList(), any(), any())).thenReturn(completed("Second answer"));

        ConversationChatResult first = conversationAgentService.chat(null, ConversationMode.CODE, "First question");
        ConversationChatResult second = conversationAgentService.chat(first.conversationId(), "Follow-up");

        assertTrue(first.conversationCreated());
        assertFalse(second.conversationCreated());
        assertEquals(ConversationMode.CODE, conversationService.get(first.conversationId()).mode());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeepSeekMessage>> history = ArgumentCaptor.forClass(List.class);
        verify(agentLoop).run(eq("Follow-up"), history.capture(), any(), any());
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

    /** 验证未选择项目时创建纯对话，并使用不带工具的模型循环。 */
    @Test
    void createsChatOnlyConversationWithoutWorkspaceTools() {
        when(agentLoop.runWithoutTools(eq("Explain this code"), anyList(), any(), any()))
                .thenReturn(completed("Explanation"));

        ConversationChatResult result = conversationAgentService.chat(null, ConversationMode.CHAT, "Explain this code");

        assertTrue(result.conversationCreated());
        assertEquals(ConversationMode.CHAT, conversationService.get(result.conversationId()).mode());
        verify(agentLoop).runWithoutTools(eq("Explain this code"), anyList(), any(), any());
    }

    /** 验证失败用户轮次不会进入下一次模型上下文。 */
    @Test
    void excludesFailedTurnFromFollowingContext() {
        when(agentLoop.run(eq("Broken request"), anyList(), any(), any()))
                .thenThrow(new IllegalStateException("model unavailable"));

        assertThrows(
                IllegalStateException.class,
                () -> conversationAgentService.chat(null, ConversationMode.CODE, "Broken request")
        );
        String conversationId = conversationService.list(10).getFirst().id();
        when(agentLoop.run(eq("Retry"), anyList(), any(), any())).thenReturn(completed("Recovered"));

        conversationAgentService.chat(conversationId, "Retry");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeepSeekMessage>> history = ArgumentCaptor.forClass(List.class);
        verify(agentLoop).run(eq("Retry"), history.capture(), any(), any());
        assertTrue(history.getValue().isEmpty());
        MessagePage page = conversationService.messages(conversationId, null, 10);
        assertEquals(ConversationMessage.Status.ERROR, page.messages().getFirst().status());
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
