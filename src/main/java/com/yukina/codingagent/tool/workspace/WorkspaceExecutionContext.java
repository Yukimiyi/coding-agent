package com.yukina.codingagent.tool.workspace;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * 将当前 Agent 运行绑定到一个工作空间根目录，并在运行结束后清理线程状态。
 */
@Component
public class WorkspaceExecutionContext {

    private final Path defaultRoot;
    private final ThreadLocal<Path> activeRoot = new ThreadLocal<>();

    /** 使用全局配置目录作为无会话任务的默认工作空间。 */
    public WorkspaceExecutionContext(WorkspaceProperties properties) {
        this.defaultRoot = requireDirectory(properties.root());
    }

    /** 返回当前运行根目录；未绑定时返回默认根目录。 */
    public Path root() {
        Path root = activeRoot.get();
        return root == null ? defaultRoot : root;
    }

    /**
     * 在指定根目录中执行操作，并保证异常或取消后恢复原上下文。
     */
    public <T> T withWorkspace(Path root, Supplier<T> action) {
        if (action == null) {
            throw new IllegalArgumentException("workspace action must not be null");
        }
        Path safeRoot = requireDirectory(root);
        Path previous = activeRoot.get();
        activeRoot.set(safeRoot);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                activeRoot.remove();
            } else {
                activeRoot.set(previous);
            }
        }
    }

    /** 将目录规范化为真实路径。 */
    private static Path requireDirectory(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("workspace root must not be null");
        }
        try {
            Path normalized = path.toAbsolutePath().normalize();
            Files.createDirectories(normalized);
            Path realPath = normalized.toRealPath();
            if (!Files.isDirectory(realPath)) {
                throw new IllegalArgumentException("Workspace root is not a directory: " + path);
            }
            return realPath;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Workspace root is not accessible: " + path);
        }
    }
}
