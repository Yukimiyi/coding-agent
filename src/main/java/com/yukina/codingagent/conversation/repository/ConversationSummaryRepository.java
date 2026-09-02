package com.yukina.codingagent.conversation.repository;

import com.yukina.codingagent.conversation.model.ConversationSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 使用 H2 持久化每个会话的增量滚动摘要。 */
@Repository
public class ConversationSummaryRepository {

    /** 访问 conversation_summaries 表的 JDBC 模板。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建会话摘要仓储。
     *
     * @param jdbcTemplate 数据库访问模板
     */
    public ConversationSummaryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查询指定会话的最新摘要。
     *
     * @param conversationId 会话 ID
     * @return 摘要不存在时为空
     */
    public Optional<ConversationSummary> find(String conversationId) {
        List<ConversationSummary> summaries = jdbcTemplate.query(
                "SELECT conversation_id, summary, last_message_id, updated_at "
                        + "FROM conversation_summaries WHERE conversation_id = ?",
                (resultSet, rowNumber) -> new ConversationSummary(
                        resultSet.getString("conversation_id"),
                        resultSet.getString("summary"),
                        resultSet.getLong("last_message_id"),
                        resultSet.getTimestamp("updated_at").toInstant()
                ),
                conversationId
        );
        return summaries.stream().findFirst();
    }

    /**
     * 原子插入或更新摘要及其消息水位。
     *
     * @param conversationId 会话 ID
     * @param summary 规范化结构化摘要
     * @param lastMessageId 已覆盖的最后成功消息 ID
     * @param now 更新时间
     */
    public void upsert(String conversationId, String summary, long lastMessageId, Instant now) {
        jdbcTemplate.update(
                "MERGE INTO conversation_summaries "
                        + "(conversation_id, summary, last_message_id, updated_at) KEY(conversation_id) "
                        + "VALUES (?, ?, ?, ?)",
                conversationId,
                summary,
                lastMessageId,
                Timestamp.from(now)
        );
    }
}
