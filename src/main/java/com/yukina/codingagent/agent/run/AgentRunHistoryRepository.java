package com.yukina.codingagent.agent.run;

import com.yukina.codingagent.agent.AgentRunResult;
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

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /** 创建运行历史仓储。 */
    public AgentRunHistoryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** 保存一条终态运行快照。 */
    public void save(AgentRunSnapshot snapshot) {
        jdbcTemplate.update(
                "INSERT INTO agent_runs(run_id, request_id, conversation_id, workspace_id, status, "
                        + "created_at, started_at, finished_at, tool_steps_json, result_json, error_message) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                snapshot.runId(),
                snapshot.requestId(),
                snapshot.conversationId(),
                snapshot.workspaceId(),
                snapshot.status().name(),
                timestamp(snapshot.createdAt()),
                timestamp(snapshot.startedAt()),
                timestamp(snapshot.finishedAt()),
                serialize(snapshot.toolSteps()),
                serialize(snapshot.result()),
                snapshot.error()
        );
    }

    /** 查询一个会话最近完成的运行。 */
    public Optional<AgentRunHistory> findLatest(String conversationId) {
        return jdbcTemplate.query(
                "SELECT run_id, request_id, conversation_id, workspace_id, status, created_at, "
                        + "started_at, finished_at, tool_steps_json, result_json, error_message FROM agent_runs "
                        + "WHERE conversation_id = ? ORDER BY finished_at DESC, run_id DESC LIMIT 1",
                this::mapHistory,
                conversationId
        ).stream().findFirst();
    }

    /** 将可选运行结果序列化为 JSON。 */
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

    /** 将数据库记录恢复为运行历史。 */
    private AgentRunHistory mapHistory(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AgentRunHistory(
                resultSet.getString("run_id"),
                resultSet.getString("request_id"),
                resultSet.getString("conversation_id"),
                resultSet.getString("workspace_id"),
                AgentRunStatus.valueOf(resultSet.getString("status")),
                instant(resultSet.getTimestamp("created_at")),
                instant(resultSet.getTimestamp("started_at")),
                instant(resultSet.getTimestamp("finished_at")),
                deserializeToolSteps(resultSet.getString("tool_steps_json")),
                deserializeResult(resultSet.getString("result_json")),
                resultSet.getString("error_message")
        );
    }

    /** 恢复可选的运行结果 JSON。 */
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

    /** 恢复独立保存的工具轨迹，覆盖失败和取消运行。 */
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

    /** 将可空时间转换为 JDBC 时间戳。 */
    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    /** 将可空 JDBC 时间戳转换为时间点。 */
    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
