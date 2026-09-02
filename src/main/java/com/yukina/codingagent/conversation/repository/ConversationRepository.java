package com.yukina.codingagent.conversation.repository;

import com.yukina.codingagent.conversation.model.Conversation;
import com.yukina.codingagent.conversation.model.ConversationMessage;
import com.yukina.codingagent.conversation.model.ConversationMode;
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

    /** 访问 conversations 和 conversation_messages 表的 JDBC 模板。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建会话仓储。
     *
     * @param jdbcTemplate 数据库访问模板
     */
    public ConversationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入并返回新会话。
     *
     * @param id 会话 ID
     * @param title 规范化标题
     * @param mode 会话模式
     * @param now 创建及更新时间
     * @return 与插入记录一致的会话
     */
    public Conversation create(String id, String title, ConversationMode mode, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO conversations(id, title, mode, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                id,
                title,
                mode.name(),
                Timestamp.from(now),
                Timestamp.from(now)
        );
        return new Conversation(id, title, mode, now, now);
    }

    /**
     * 插入用于旧单元测试或迁移场景的未绑定会话。
     *
     * @param id 会话 ID
     * @param title 规范化标题
     * @param now 创建及更新时间
     * @return 新建纯聊天会话
     */
    public Conversation create(String id, String title, Instant now) {
        return create(id, title, ConversationMode.CHAT, now);
    }

    /**
     * 按 ID 查询会话。
     *
     * @param id 会话 ID
     * @return 包含匹配会话的 Optional，不存在时为空
     */
    public Optional<Conversation> findById(String id) {
        List<Conversation> conversations = jdbcTemplate.query(
                "SELECT id, title, mode, created_at, updated_at FROM conversations WHERE id = ?",
                this::mapConversation,
                id
        );
        return conversations.stream().findFirst();
    }

    /**
     * 按最近活动时间倒序查询会话。
     *
     * @param limit 最大返回数量
     * @return 最近活动会话列表
     */
    public List<Conversation> listRecent(int limit) {
        return jdbcTemplate.query(
                "SELECT id, title, mode, created_at, updated_at "
                        + "FROM conversations ORDER BY updated_at DESC, id DESC LIMIT ?",
                this::mapConversation,
                limit
        );
    }

    /**
     * 更新会话标题和最近活动时间。
     *
     * @param id 会话 ID
     * @param title 新标题
     * @param now 更新时间
     * @return 有记录被更新时返回 {@code true}
     */
    public boolean updateTitle(String id, String title, Instant now) {
        return jdbcTemplate.update(
                "UPDATE conversations SET title = ?, updated_at = ? WHERE id = ?",
                title,
                Timestamp.from(now),
                id
        ) > 0;
    }

    /**
     * 更新会话最近活动时间。
     *
     * @param id 会话 ID
     * @param now 更新时间
     */
    public void touch(String id, Instant now) {
        jdbcTemplate.update(
                "UPDATE conversations SET updated_at = ? WHERE id = ?",
                Timestamp.from(now),
                id
        );
    }

    /**
     * 删除会话；关联消息由数据库外键级联删除。
     *
     * @param id 会话 ID
     * @return 有记录被删除时返回 {@code true}
     */
    public boolean delete(String id) {
        return jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", id) > 0;
    }

    /**
     * 追加消息并返回数据库生成的稳定消息 ID。
     *
     * @param conversationId 会话 ID
     * @param role 用户或助手角色
     * @param content 消息文本
     * @param status PENDING、SUCCESS 或 ERROR 状态
     * @param now 创建时间
     * @return 包含数据库生成 ID 的消息
     * @throws IllegalStateException 数据库未返回生成键时抛出
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
     * 更新一条消息的执行状态。
     *
     * @param conversationId 会话 ID
     * @param messageId 消息 ID
     * @param status 新状态
     * @return 有记录被更新时返回 {@code true}
     */
    public boolean updateMessageStatus(
            String conversationId,
            long messageId,
            ConversationMessage.Status status
    ) {
        return jdbcTemplate.update(
                "UPDATE conversation_messages SET status = ? WHERE conversation_id = ? AND id = ?",
                status.name(),
                conversationId,
                messageId
        ) > 0;
    }

    /**
     * 查询最近成功消息，并转换为模型需要的时间正序。
     *
     * @param conversationId 会话 ID
     * @param limit 最大返回数量
     * @return 最近成功消息的时间正序列表
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
     * 按时间正序查询摘要水位之后的成功消息。
     *
     * @param conversationId 会话 ID
     * @param afterId 已经进入摘要的最后消息 ID；没有摘要时为零
     * @param limit 单次最大读取数量
     * @return 水位之后的成功用户和助手消息
     */
    public List<ConversationMessage> findSuccessfulMessagesAfter(
            String conversationId,
            long afterId,
            int limit
    ) {
        return jdbcTemplate.query(
                "SELECT id, conversation_id, role, content, status, created_at "
                        + "FROM conversation_messages "
                        + "WHERE conversation_id = ? AND status = 'SUCCESS' AND id > ? "
                        + "ORDER BY id ASC LIMIT ?",
                this::mapMessage,
                conversationId,
                afterId,
                limit
        );
    }

    /**
     * 查询指定 ID 之前的消息；额外读取一条用于判断是否存在下一页。
     *
     * @param conversationId 会话 ID
     * @param beforeId 可选上页最小消息 ID
     * @param limit 页面大小
     * @return 消息页、下一游标及是否还有更早消息
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

    /**
     * 将 JDBC 行映射为会话消息。
     *
     * @param resultSet 当前结果集行
     * @param rowNumber 当前行号，映射逻辑无需使用
     * @return 映射得到的会话消息
     * @throws java.sql.SQLException 读取数据库列失败时抛出
     */
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

    /**
     * 将 JDBC 行映射为会话元数据。
     *
     * @param resultSet 当前结果集行
     * @param rowNumber 当前行号，映射逻辑无需使用
     * @return 映射得到的会话
     * @throws java.sql.SQLException 读取数据库列失败时抛出
     */
    private Conversation mapConversation(java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        return new Conversation(
                resultSet.getString("id"),
                resultSet.getString("title"),
                ConversationMode.valueOf(resultSet.getString("mode")),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()
        );
    }
}
