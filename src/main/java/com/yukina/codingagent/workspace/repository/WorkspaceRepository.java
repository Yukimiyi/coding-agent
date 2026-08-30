package com.yukina.codingagent.workspace.repository;

import com.yukina.codingagent.workspace.model.Workspace;
import com.yukina.codingagent.workspace.model.WorkspaceType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 使用 JdbcTemplate 持久化工作空间注册信息。
 */
@Repository
public class WorkspaceRepository {

    private final JdbcTemplate jdbcTemplate;

    /** 创建工作空间仓储。 */
    public WorkspaceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 插入一个工作空间。 */
    public Workspace create(String id, String name, WorkspaceType type, String rootPath, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO workspaces(id, name, type, root_path, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                id,
                name,
                type.name(),
                rootPath,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        return new Workspace(id, name, type, rootPath, now, now);
    }

    /** 创建托管工作空间，兼容未显式传入类型的调用方。 */
    public Workspace create(String id, String name, String rootPath, Instant now) {
        return create(id, name, WorkspaceType.MANAGED, rootPath, now);
    }

    /** 按 ID 查询工作空间。 */
    public Optional<Workspace> findById(String id) {
        return jdbcTemplate.query(
                "SELECT id, name, type, root_path, created_at, updated_at FROM workspaces WHERE id = ?",
                this::mapWorkspace,
                id
        ).stream().findFirst();
    }

    /** 按规范化根目录查询工作空间。 */
    public Optional<Workspace> findByRootPath(String rootPath) {
        return jdbcTemplate.query(
                "SELECT id, name, type, root_path, created_at, updated_at FROM workspaces WHERE root_path = ?",
                this::mapWorkspace,
                rootPath
        ).stream().findFirst();
    }

    /** 按名称和 ID 稳定排序列出全部工作空间。 */
    public List<Workspace> list() {
        return jdbcTemplate.query(
                "SELECT id, name, type, root_path, created_at, updated_at FROM workspaces ORDER BY name, id",
                this::mapWorkspace
        );
    }

    /** 返回当前注册数量。 */
    public int count() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM workspaces", Integer.class);
        return count == null ? 0 : count;
    }

    /** 修改工作空间名称。 */
    public boolean rename(String id, String name, Instant now) {
        return jdbcTemplate.update(
                "UPDATE workspaces SET name = ?, updated_at = ? WHERE id = ?",
                name,
                Timestamp.from(now),
                id
        ) > 0;
    }

    /** 修改项目的托管根路径，用于安全迁移旧目录。 */
    public boolean updateRootPath(String id, String rootPath, Instant now) {
        return jdbcTemplate.update(
                "UPDATE workspaces SET root_path = ?, updated_at = ? WHERE id = ?",
                rootPath,
                Timestamp.from(now),
                id
        ) > 0;
    }

    /** 统计绑定到指定工作空间的会话数量。 */
    public int countConversations(String workspaceId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversations WHERE workspace_id = ?",
                Integer.class,
                workspaceId
        );
        return count == null ? 0 : count;
    }

    /** 删除未被会话引用的工作空间注册信息。 */
    public boolean delete(String id) {
        return jdbcTemplate.update("DELETE FROM workspaces WHERE id = ?", id) > 0;
    }

    /** 将 JDBC 行映射为工作空间。 */
    private Workspace mapWorkspace(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Workspace(
                resultSet.getString("id"),
                resultSet.getString("name"),
                WorkspaceType.valueOf(resultSet.getString("type")),
                resultSet.getString("root_path"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()
        );
    }
}
