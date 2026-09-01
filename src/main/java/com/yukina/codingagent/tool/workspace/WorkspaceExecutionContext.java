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

    /**
     * 使用全局配置目录作为无会话任务的默认工作空间。
     *
     * @param properties 默认工作空间根目录配置
     * @throws IllegalArgumentException 根目录无法创建或访问时抛出
     */
    public WorkspaceExecutionContext(WorkspaceProperties properties) {
        this.defaultRoot = requireDirectory(properties.root());
    }

    /**
     * 返回当前运行根目录；未绑定时返回默认根目录。
     *
     * @return 当前线程绑定或默认的真实工作空间路径
     */
    public Path root() {
        Path root = activeRoot.get();
        return root == null ? defaultRoot : root;
    }

    /**
     * 在指定根目录中执行操作，并保证异常或取消后恢复原上下文。
     *
     * @param root 本次操作使用的工作空间根目录
     * @param action 在绑定上下文中执行的任务
     * @param <T> 任务返回值类型
     * @return 任务执行结果
     * @throws IllegalArgumentException 任务为空或根目录不可访问时抛出
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

    /**
     * 将目录规范化为真实路径，目录不存在时先创建。
     *
     * @param path 待解析目录
     * @return 已存在的真实绝对目录
     * @throws IllegalArgumentException 路径为空、不是目录或不可访问时抛出
     */
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
