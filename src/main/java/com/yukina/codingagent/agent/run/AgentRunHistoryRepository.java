package com.yukina.codingagent.agent.run;

import com.yukina.codingagent.agent.AgentRunResult;
import com.yukina.codingagent.conversation.model.ConversationMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.List;

/** 使用 H2 持久化终态运行及其完整工具轨迹。 */
@Repository
public class AgentRunHistoryRepository {

    /** 访问 agent_runs 表的 Spring JDBC 模板。 */
    private final JdbcTemplate jdbcTemplate;
    /** 序列化和反序列化工具轨迹及最终结果的 JSON 映射器。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建运行历史仓储。
     *
     * @param jdbcTemplate 数据库访问模板
     * @param objectMapper 工具轨迹和结果 JSON 转换器
     */
    public AgentRunHistoryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 保存一条终态运行快照。
     *
     * @param snapshot 已进入终态的运行快照
     * @throws IllegalStateException 工具轨迹或结果无法序列化时抛出
     */
    public void save(AgentRunSnapshot snapshot) {
        jdbcTemplate.update(
                "INSERT INTO agent_runs(run_id, request_id, conversation_id, conversation_mode, status, "
                        + "created_at, started_at, finished_at, tool_steps_json, result_json, error_message) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                snapshot.runId(),
                snapshot.requestId(),
                snapshot.conversationId(),
                snapshot.mode().name(),
                snapshot.status().name(),
                timestamp(snapshot.createdAt()),
                timestamp(snapshot.startedAt()),
                timestamp(snapshot.finishedAt()),
                serialize(snapshot.toolSteps()),
                serialize(snapshot.result()),
                snapshot.error()
        );
    }

    /**
     * 查询一个会话最近完成的运行。
     *
     * @param conversationId 会话 ID
     * @return 最近终态运行；不存在时为空
     */
    public Optional<AgentRunHistory> findLatest(String conversationId) {
        return jdbcTemplate.query(
                "SELECT run_id, request_id, conversation_id, conversation_mode, status, created_at, "
                        + "started_at, finished_at, tool_steps_json, result_json, error_message FROM agent_runs "
                        + "WHERE conversation_id = ? ORDER BY finished_at DESC, run_id DESC LIMIT 1",
                this::mapHistory,
                conversationId
        ).stream().findFirst();
    }

    /**
     * 将可选运行结果序列化为 JSON。
     *
     * @param value 工具轨迹、运行结果或 {@code null}
     * @return JSON 字符串；输入为 {@code null} 时返回 {@code null}
     * @throws IllegalStateException 序列化失败时抛出
     */
    private String serialize(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize Agent run result", exception);
        }
    }

    /**
     * 将数据库记录恢复为运行历史。
     *
     * @param resultSet 当前结果集行
     * @param rowNumber 当前行号，映射逻辑无需使用
     * @return 恢复后的终态运行历史
     * @throws SQLException 读取数据库列失败时抛出
     */
    private AgentRunHistory mapHistory(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AgentRunHistory(
                resultSet.getString("run_id"),
                resultSet.getString("request_id"),
                resultSet.getString("conversation_id"),
                ConversationMode.valueOf(resultSet.getString("conversation_mode")),
                AgentRunStatus.valueOf(resultSet.getString("status")),
                instant(resultSet.getTimestamp("created_at")),
                instant(resultSet.getTimestamp("started_at")),
                instant(resultSet.getTimestamp("finished_at")),
                deserializeToolSteps(resultSet.getString("tool_steps_json")),
                deserializeResult(resultSet.getString("result_json")),
                resultSet.getString("error_message")
        );
    }

    /**
     * 恢复可选的运行结果 JSON。
     *
     * @param json 数据库存储的结果 JSON
     * @return Agent 结果；空值返回 {@code null}
     * @throws IllegalStateException JSON 无法反序列化时抛出
     */
    private AgentRunResult deserializeResult(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, AgentRunResult.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to deserialize Agent run result", exception);
        }
    }

    /**
     * 恢复独立保存的工具轨迹，覆盖失败和取消运行。
     *
     * @param json 数据库存储的工具轨迹 JSON
     * @return 不可变工具轨迹列表；空值返回空列表
     * @throws IllegalStateException JSON 无法反序列化时抛出
     */
    private List<AgentRunResult.ToolStep> deserializeToolSteps(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            AgentRunResult.ToolStep[] steps = objectMapper.readValue(json, AgentRunResult.ToolStep[].class);
            return List.of(steps);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to deserialize Agent tool steps", exception);
        }
    }

    /**
     * 将可空时间转换为 JDBC 时间戳。
     *
     * @param instant 可空时间点
     * @return JDBC 时间戳；输入为空时返回 {@code null}
     */
    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    /**
     * 将可空 JDBC 时间戳转换为时间点。
     *
     * @param timestamp 可空 JDBC 时间戳
     * @return 时间点；输入为空时返回 {@code null}
     */
    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
