package com.yukina.codingagent.agent.run;

import com.yukina.codingagent.agent.AgentRunResult;
import com.yukina.codingagent.conversation.model.ConversationMode;
import com.yukina.codingagent.conversation.repository.ConversationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证终态运行和工具轨迹可以跨进程恢复。 */
class AgentRunHistoryRepositoryTest {

    private EmbeddedDatabase database;
    private AgentRunHistoryRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .addScript("schema.sql")
                .build();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(database);
        new ConversationRepository(jdbcTemplate).create("conversation-1", "History", Instant.now());
        repository = new AgentRunHistoryRepository(jdbcTemplate, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void savesAndRestoresLatestResult() {
        Instant now = Instant.now();
        AgentRunResult result = new AgentRunResult(
                "Done",
                "deepseek-test",
                2,
                true,
                AgentRunResult.StopReason.COMPLETED,
                List.of(),
                new AgentRunResult.Usage(3, 2, 5)
        );
        repository.save(new AgentRunSnapshot(
                "run-1",
                "request-1",
                "conversation-1",
                ConversationMode.CHAT,
                false,
                AgentRunStatus.COMPLETED,
                now,
                now,
                now,
                2,
                List.of(),
                "Done",
                result,
                null,
                5
        ));

        AgentRunHistory restored = repository.findLatest("conversation-1").orElseThrow();

        assertEquals("run-1", restored.runId());
        assertEquals("Done", restored.result().answer());
        assertTrue(restored.toolSteps().isEmpty());
        assertTrue(restored.result().completed());
        assertEquals(5, restored.result().usage().totalTokens());
    }
}
