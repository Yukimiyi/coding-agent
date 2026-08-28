package com.yukina.codingagent.conversation.memory;

import com.yukina.codingagent.conversation.model.ConversationMessage;
import com.yukina.codingagent.conversation.repository.ConversationRepository;
import com.yukina.codingagent.deepseek.DeepSeekMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证热上下文回填、去重和 TTL 过期。 */
class ConversationContextManagerTest {

    private EmbeddedDatabase database;
    private ConversationRepository repository;
    private ConversationContextProperties properties;

    /** 创建隔离数据库、基础会话和上下文配置。 */
    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .addScript("schema.sql")
                .build();
        repository = new ConversationRepository(new JdbcTemplate(database));
        repository.create("conversation-1", "Test", Instant.now());
        properties = new ConversationContextProperties(4, 100, Duration.ofMinutes(30), 10);
    }

    /** 关闭当前用例的嵌入式数据库。 */
    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    /** 验证服务重建后从数据库恢复消息且不重复最新消息。 */
    @Test
    void reloadsContextFromDatabaseWithoutDuplicatingLatestMessage() {
        ConversationContextManager firstManager = new ConversationContextManager(
                repository,
                new InMemoryConversationMemoryStore(properties),
                properties
        );
        firstManager.appendSuccess("conversation-1", ConversationMessage.Role.USER, "question");
        firstManager.appendSuccess("conversation-1", ConversationMessage.Role.ASSISTANT, "answer");

        ConversationContextManager restartedManager = new ConversationContextManager(
                repository,
                new InMemoryConversationMemoryStore(properties),
                properties
        );
        List<DeepSeekMessage> restored = restartedManager.load("conversation-1");

        assertEquals(2, restored.size());
        assertEquals(List.of("question", "answer"), restored.stream()
                .map(DeepSeekMessage::content)
                .toList());
    }

    /** 验证热上下文超过 TTL 后失效。 */
    @Test
    void expiresHotMemoryAfterTtl() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-27T00:00:00Z"));
        ConversationContextProperties shortTtl = new ConversationContextProperties(
                4,
                100,
                Duration.ofSeconds(10),
                10
        );
        InMemoryConversationMemoryStore store = new InMemoryConversationMemoryStore(shortTtl, clock);
        store.put("conversation-1", List.of(DeepSeekMessage.user("hello")));

        clock.advance(Duration.ofSeconds(11));

        assertTrue(store.get("conversation-1").isEmpty());
    }

    /** 支持手动推进时间的测试时钟。 */
    private static final class MutableClock extends Clock {
        private Instant instant;

        /** 创建指定初始时刻的测试时钟。 */
        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        /** 推进测试时钟。 */
        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        /** {@inheritDoc} */
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /** {@inheritDoc} */
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        /** {@inheritDoc} */
        @Override
        public Instant instant() {
            return instant;
        }
    }
}
