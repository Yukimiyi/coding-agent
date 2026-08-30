package com.yukina.codingagent.workspace.service;

import com.yukina.codingagent.workspace.WorkspaceRegistryProperties;
import com.yukina.codingagent.workspace.exception.WorkspaceConflictException;
import com.yukina.codingagent.workspace.exception.WorkspaceNotFoundException;
import com.yukina.codingagent.workspace.model.Workspace;
import com.yukina.codingagent.workspace.repository.WorkspaceRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 管理可容纳多个对话的持久化项目工作空间。
 */
@Service
public class WorkspaceService {

    private static final int MAX_NAME_LENGTH = 120;

    private final WorkspaceRepository repository;
    private final WorkspaceRegistryProperties registryProperties;
    private Path storageRoot;

    /** 创建托管项目领域服务。 */
    public WorkspaceService(
            WorkspaceRepository repository,
            WorkspaceRegistryProperties registryProperties
    ) {
        this.repository = repository;
        this.registryProperties = registryProperties;
    }

    /** 初始化项目存储容器并校验已有注册，不自动创建默认项目。 */
    @PostConstruct
    public synchronized void initialize() {
        storageRoot = createAndResolveDirectory(registryProperties.storageRoot());
        for (Workspace registered : repository.list()) {
            Workspace workspace = migrateLegacyDefaultDirectory(registered);
            if (!isManagedProject(workspace)) {
                if (repository.countConversations(workspace.id()) > 0) {
                    throw new IllegalStateException(
                            "Referenced workspace is outside the managed project storage: " + workspace.id()
                    );
                }
                repository.delete(workspace.id());
            }
        }
    }

    /** 将旧版 `.tmp/default` 项目原样迁移到其稳定 UUID 目录。 */
    private Workspace migrateLegacyDefaultDirectory(Workspace workspace) {
        Path source;
        try {
            source = Path.of(workspace.rootPath()).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            return workspace;
        }
        if (!storageRoot.equals(source.getParent())
                || !"default".equals(source.getFileName().toString())) {
            return workspace;
        }

        Path target = storageRoot.resolve(workspace.id()).normalize();
        try {
            if (Files.exists(target)) {
                throw new WorkspaceConflictException("Legacy project migration target already exists");
            }
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(source, target);
            }
            repository.updateRootPath(workspace.id(), target.toRealPath().toString(), Instant.now());
            if ("默认工作空间".equals(workspace.name())) {
                repository.rename(workspace.id(), "已有项目", Instant.now());
            }
            return get(workspace.id());
        } catch (IOException exception) {
            throw new WorkspaceConflictException("Unable to migrate the legacy default project");
        }
    }

    /** 按 ID 查询项目工作空间。 */
    public Workspace get(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId must not be blank");
        }
        return repository.findById(workspaceId)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceId));
    }

    /** 列出所有持久化项目工作空间。 */
    public List<Workspace> list() {
        return repository.list();
    }

    /** 在托管存储目录下创建一个空白项目。 */
    public synchronized Workspace create(String name) {
        if (repository.count() >= registryProperties.maxWorkspaces()) {
            throw new WorkspaceConflictException("Project workspace limit reached");
        }
        String workspaceId = UUID.randomUUID().toString();
        Path root = createManagedDirectory(workspaceId);
        try {
            return repository.create(
                    workspaceId,
                    normalizeName(name, "新项目"),
                    root.toString(),
                    Instant.now()
            );
        } catch (RuntimeException exception) {
            deleteEmptyDirectory(root);
            throw exception;
        }
    }

    /** 修改项目展示名称。 */
    public Workspace rename(String workspaceId, String name) {
        Workspace existing = get(workspaceId);
        String normalized = normalizeName(name, existing.name());
        if (!repository.rename(workspaceId, normalized, Instant.now())) {
            throw new WorkspaceNotFoundException(workspaceId);
        }
        return get(workspaceId);
    }

    /** 删除未绑定对话的项目注册，并在项目为空时回收目录。 */
    public synchronized void delete(String workspaceId) {
        Workspace workspace = get(workspaceId);
        if (repository.countConversations(workspaceId) > 0) {
            throw new WorkspaceConflictException("Project still has conversations");
        }
        if (!repository.delete(workspaceId)) {
            throw new WorkspaceNotFoundException(workspaceId);
        }
        deleteEmptyDirectory(Path.of(workspace.rootPath()));
    }

    /** 将项目工作空间转换为工具执行根目录。 */
    public Path rootPath(Workspace workspace) {
        return Path.of(workspace.rootPath());
    }

    /** 判断已有注册是否为存储容器中的直属项目目录。 */
    private boolean isManagedProject(Workspace workspace) {
        try {
            Path root = Path.of(workspace.rootPath()).toAbsolutePath().normalize();
            return storageRoot.equals(root.getParent()) && Files.isDirectory(root);
        } catch (InvalidPathException exception) {
            return false;
        }
    }

    /** 创建或解析项目存储容器。 */
    private static Path createAndResolveDirectory(Path path) {
        try {
            Files.createDirectories(path.toAbsolutePath().normalize());
            return path.toAbsolutePath().normalize().toRealPath();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Project storage cannot be created: " + path);
        }
    }

    /** 创建由服务端生成 ID 命名的项目目录。 */
    private Path createManagedDirectory(String workspaceId) {
        try {
            Path root = storageRoot.resolve(workspaceId).normalize();
            if (!storageRoot.equals(root.getParent())) {
                throw new IllegalArgumentException("Project root must be managed by the server");
            }
            return Files.createDirectory(root).toRealPath();
        } catch (IOException exception) {
            throw new WorkspaceConflictException("Unable to create project workspace");
        }
    }

    /** 规范化项目名称。 */
    private static String normalizeName(String name, String fallback) {
        String normalized = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            normalized = fallback;
        }
        return normalized.length() <= MAX_NAME_LENGTH
                ? normalized
                : normalized.substring(0, MAX_NAME_LENGTH);
    }

    /** 尽力回收空项目目录，不删除其中已有文件。 */
    private static void deleteEmptyDirectory(Path root) {
        try {
            Files.deleteIfExists(root);
        } catch (IOException ignored) {
            // 非空项目保留文件，避免移除注册时造成意外数据丢失。
        }
    }
}
