package com.yukina.codingagent.conversation.repository;

import com.yukina.codingagent.conversation.model.ConversationMessage;
import com.yukina.codingagent.conversation.model.MessagePage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证会话仓储的稳定分页和上下文查询。 */
class ConversationRepositoryTest {

    private EmbeddedDatabase database;
    private ConversationRepository repository;

    /** 创建隔离数据库并插入基础会话。 */
    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .addScript("schema.sql")
                .build();
        repository = new ConversationRepository(new JdbcTemplate(database));
        repository.create("conversation-1", "Test", Instant.now());
    }

    /** 关闭当前用例的嵌入式数据库。 */
    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    /** 验证消息 ID 游标在多页查询中不会重复或遗漏。 */
    @Test
    void pagesMessagesUsingStableIdCursor() {
        append(ConversationMessage.Role.USER, "one", ConversationMessage.Status.SUCCESS);
        append(ConversationMessage.Role.ASSISTANT, "two", ConversationMessage.Status.SUCCESS);
        append(ConversationMessage.Role.USER, "three", ConversationMessage.Status.SUCCESS);
        append(ConversationMessage.Role.ASSISTANT, "four", ConversationMessage.Status.SUCCESS);

        MessagePage firstPage = repository.findMessagesBefore("conversation-1", null, 2);
        MessagePage secondPage = repository.findMessagesBefore(
                "conversation-1",
                firstPage.nextCursor(),
                2
        );

        assertEquals(List.of("three", "four"), firstPage.messages().stream()
                .map(ConversationMessage::content)
                .toList());
        assertTrue(firstPage.hasMore());
        assertEquals(List.of("one", "two"), secondPage.messages().stream()
                .map(ConversationMessage::content)
                .toList());
        assertFalse(secondPage.hasMore());
    }

    /** 验证上下文仅恢复成功消息且保持时间正序。 */
    @Test
    void restoresOnlySuccessfulContextInChronologicalOrder() {
        append(ConversationMessage.Role.USER, "first", ConversationMessage.Status.SUCCESS);
        append(ConversationMessage.Role.ASSISTANT, "failed", ConversationMessage.Status.ERROR);
        append(ConversationMessage.Role.ASSISTANT, "second", ConversationMessage.Status.SUCCESS);

        List<ConversationMessage> context = repository.findRecentSuccessfulMessages("conversation-1", 20);

        assertEquals(List.of("first", "second"), context.stream()
                .map(ConversationMessage::content)
                .toList());
    }

    /** 向测试会话追加消息。 */
    private void append(
            ConversationMessage.Role role,
            String content,
            ConversationMessage.Status status
    ) {
        repository.appendMessage("conversation-1", role, content, status, Instant.now());
    }
}
