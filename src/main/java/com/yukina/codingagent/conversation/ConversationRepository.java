package com.yukina.codingagent.conversation;

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

@Repository
public class ConversationRepository {

    private final JdbcTemplate jdbcTemplate;

    public ConversationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Conversation create(String id, String title, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO conversations(id, title, created_at, updated_at) VALUES (?, ?, ?, ?)",
                id,
                title,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        return new Conversation(id, title, now, now);
    }

    public Optional<Conversation> findById(String id) {
        List<Conversation> conversations = jdbcTemplate.query(
                "SELECT id, title, created_at, updated_at FROM conversations WHERE id = ?",
                (resultSet, rowNumber) -> new Conversation(
                        resultSet.getString("id"),
                        resultSet.getString("title"),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getTimestamp("updated_at").toInstant()
                ),
                id
        );
        return conversations.stream().findFirst();
    }

    public List<Conversation> listRecent(int limit) {
        return jdbcTemplate.query(
                "SELECT id, title, created_at, updated_at "
                        + "FROM conversations ORDER BY updated_at DESC, id DESC LIMIT ?",
                (resultSet, rowNumber) -> new Conversation(
                        resultSet.getString("id"),
                        resultSet.getString("title"),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getTimestamp("updated_at").toInstant()
                ),
                limit
        );
    }

    public boolean updateTitle(String id, String title, Instant now) {
        return jdbcTemplate.update(
                "UPDATE conversations SET title = ?, updated_at = ? WHERE id = ?",
                title,
                Timestamp.from(now),
                id
        ) > 0;
    }

    public void touch(String id, Instant now) {
        jdbcTemplate.update(
                "UPDATE conversations SET updated_at = ? WHERE id = ?",
                Timestamp.from(now),
                id
        );
    }

    public boolean delete(String id) {
        return jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", id) > 0;
    }

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
}
