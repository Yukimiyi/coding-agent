package com.yukina.codingagent.conversation.repository;

import com.yukina.codingagent.conversation.model.Conversation;
import com.yukina.codingagent.conversation.model.ConversationMessage;
import com.yukina.codingagent.conversation.model.MessagePage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 使用 JdbcTemplate 持久化会话和消息，并提供稳定游标查询。
 */
@Repository
public class ConversationRepository {

    private final JdbcTemplate jdbcTemplate;

    /** 创建会话仓储。 */
    public ConversationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 插入并返回新会话。 */
    public Conversation create(String id, String title, String workspaceId, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO conversations(id, title, workspace_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                id,
                title,
                workspaceId,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        return new Conversation(id, title, workspaceId, now, now);
    }

    /** 插入用于旧单元测试或迁移场景的未绑定会话。 */
    public Conversation create(String id, String title, Instant now) {
        return create(id, title, null, now);
    }

    /** 按 ID 查询会话。 */
    public Optional<Conversation> findById(String id) {
        List<Conversation> conversations = jdbcTemplate.query(
                "SELECT id, title, workspace_id, created_at, updated_at FROM conversations WHERE id = ?",
                this::mapConversation,
                id
        );
        return conversations.stream().findFirst();
    }

    /** 按最近活动时间倒序查询会话。 */
    public List<Conversation> listRecent(int limit) {
        return jdbcTemplate.query(
                "SELECT id, title, workspace_id, created_at, updated_at "
                        + "FROM conversations ORDER BY updated_at DESC, id DESC LIMIT ?",
                this::mapConversation,
                limit
        );
    }

    /** 按工作空间过滤并按最近活动时间倒序查询会话。 */
    public List<Conversation> listRecent(String workspaceId, int limit) {
        return jdbcTemplate.query(
                "SELECT id, title, workspace_id, created_at, updated_at FROM conversations "
                        + "WHERE workspace_id = ? ORDER BY updated_at DESC, id DESC LIMIT ?",
                this::mapConversation,
                workspaceId,
                limit
        );
    }

    /** 更新会话标题和最近活动时间。 */
    public boolean updateTitle(String id, String title, Instant now) {
        return jdbcTemplate.update(
                "UPDATE conversations SET title = ?, updated_at = ? WHERE id = ?",
                title,
                Timestamp.from(now),
                id
        ) > 0;
    }

    /** 更新会话最近活动时间。 */
    public void touch(String id, Instant now) {
        jdbcTemplate.update(
                "UPDATE conversations SET updated_at = ? WHERE id = ?",
                Timestamp.from(now),
                id
        );
    }

    /** 删除会话；关联消息由数据库外键级联删除。 */
    public boolean delete(String id) {
        return jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", id) > 0;
    }

    /**
     * 追加消息并返回数据库生成的稳定消息 ID。
     */
    public ConversationMessage appendMessage(
            String conversationId,
            ConversationMessage.Role role,
            String content,
            ConversationMessage.Status status,
            Instant now
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO conversation_messages(conversation_id, role, content, status, created_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setString(1, conversationId);
            statement.setString(2, role.name());
            statement.setString(3, content);
            statement.setString(4, status.name());
            statement.setTimestamp(5, Timestamp.from(now));
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Database did not return a message id");
        }
        return new ConversationMessage(key.longValue(), conversationId, role, content, status, now);
    }

    /**
     * 查询最近成功消息，并转换为模型需要的时间正序。
     */
    public List<ConversationMessage> findRecentSuccessfulMessages(String conversationId, int limit) {
        List<ConversationMessage> messages = jdbcTemplate.query(
                "SELECT id, conversation_id, role, content, status, created_at "
                        + "FROM conversation_messages "
                        + "WHERE conversation_id = ? AND status = 'SUCCESS' "
                        + "ORDER BY id DESC LIMIT ?",
                this::mapMessage,
                conversationId,
                limit
        );
        Collections.reverse(messages);
        return messages;
    }

    /**
     * 查询指定 ID 之前的消息；额外读取一条用于判断是否存在下一页。
     */
    public MessagePage findMessagesBefore(String conversationId, Long beforeId, int limit) {
        int queryLimit = limit + 1;
        List<ConversationMessage> descending;
        if (beforeId == null) {
            descending = jdbcTemplate.query(
                    "SELECT id, conversation_id, role, content, status, created_at "
                            + "FROM conversation_messages WHERE conversation_id = ? "
                            + "ORDER BY id DESC LIMIT ?",
                    this::mapMessage,
                    conversationId,
                    queryLimit
            );
        } else {
            descending = jdbcTemplate.query(
                    "SELECT id, conversation_id, role, content, status, created_at "
                            + "FROM conversation_messages WHERE conversation_id = ? AND id < ? "
                            + "ORDER BY id DESC LIMIT ?",
                    this::mapMessage,
                    conversationId,
                    beforeId,
                    queryLimit
            );
        }

        boolean hasMore = descending.size() > limit;
        List<ConversationMessage> visible = new ArrayList<>(
                descending.subList(0, Math.min(limit, descending.size()))
        );
        Collections.reverse(visible);
        Long nextCursor = hasMore && !visible.isEmpty() ? visible.getFirst().id() : null;
        return new MessagePage(visible, nextCursor, hasMore);
    }

    /** 将 JDBC 行映射为会话消息。 */
    private ConversationMessage mapMessage(java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        return new ConversationMessage(
                resultSet.getLong("id"),
                resultSet.getString("conversation_id"),
                ConversationMessage.Role.valueOf(resultSet.getString("role")),
                resultSet.getString("content"),
                ConversationMessage.Status.valueOf(resultSet.getString("status")),
                resultSet.getTimestamp("created_at").toInstant()
        );
    }

    /** 将 JDBC 行映射为会话元数据。 */
    private Conversation mapConversation(java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        return new Conversation(
                resultSet.getString("id"),
                resultSet.getString("title"),
                resultSet.getString("workspace_id"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()
        );
    }
}
