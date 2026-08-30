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
import com.yukina.codingagent.tool.workspace.WorkspaceExecutionContext;
import com.yukina.codingagent.tool.workspace.WorkspaceProperties;
import com.yukina.codingagent.workspace.model.Workspace;
import com.yukina.codingagent.workspace.service.WorkspaceService;
import com.yukina.codingagent.workspace.service.WorkspaceLockManager;
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
import java.time.Instant;
import java.util.List;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        jdbcTemplate.update(
                "INSERT INTO workspaces(id, name, root_path, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                "workspace-1",
                "Test workspace",
                workspaceRoot.toString(),
                java.sql.Timestamp.from(Instant.now()),
                java.sql.Timestamp.from(Instant.now())
        );
        ConversationRepository repository = new ConversationRepository(jdbcTemplate);
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
        Workspace workspace = new Workspace(
                "workspace-1",
                "Test workspace",
                workspaceRoot.toString(),
                Instant.now(),
                Instant.now()
        );
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        when(workspaceService.get("workspace-1")).thenReturn(workspace);
        when(workspaceService.rootPath(workspace)).thenReturn(workspaceRoot);
        WorkspaceProperties workspaceProperties = new WorkspaceProperties(
                workspaceRoot, 1024, 1024, 100, 50, 1024, 5
        );
        conversationAgentService = new ConversationAgentService(
                agentLoop,
                conversationService,
                contextManager,
                new ConversationLockManager(),
                workspaceService,
                new WorkspaceLockManager(),
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

        ConversationChatResult first = conversationAgentService.chat(null, "workspace-1", "First question");
        ConversationChatResult second = conversationAgentService.chat(first.conversationId(), "Follow-up");

        assertTrue(first.conversationCreated());
        assertFalse(second.conversationCreated());
        assertEquals("workspace-1", conversationService.get(first.conversationId()).workspaceId());
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
