package com.yukina.codingagent.tool.workspace;

import com.yukina.codingagent.tool.ToolExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * 将模型提供的相对路径限制在工作区内，并阻止符号链接逃逸。
 */
@Component
public class WorkspacePathResolver {

    private final WorkspaceExecutionContext executionContext;

    /**
     * 创建使用运行范围工作空间上下文的路径解析器。
     *
     * @param executionContext 当前线程的工作空间上下文
     */
    @Autowired
    public WorkspacePathResolver(WorkspaceExecutionContext executionContext) {
        this.executionContext = executionContext;
    }

    /**
     * 创建使用固定默认目录的独立解析器，供非 Spring 场景使用。
     *
     * @param properties 固定工作空间配置
     */
    public WorkspacePathResolver(WorkspaceProperties properties) {
        this(new WorkspaceExecutionContext(properties));
    }

    /** @return 当前运行绑定的真实工作空间根目录 */
    public Path root() {
        return executionContext.root();
    }

    /**
     * 解析并校验一个必须存在的工作区相对路径。
     *
     * @param rawPath 模型提供的工作空间相对路径
     * @return 位于工作空间内且已经存在的规范化绝对路径
     * @throws ToolExecutionException 路径无效、不存在、越界或经符号链接逃逸时抛出
     */
    public Path resolveExisting(String rawPath) {
        Path candidate = resolveLexically(rawPath);
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new ToolExecutionException("PATH_NOT_FOUND", "Path does not exist: " + display(candidate));
        }
        ensureRealPathInsideWorkspace(candidate);
        return candidate;
    }

    /**
     * 解析写入目标，并通过最近的已存在父目录验证真实路径边界。
     *
     * @param rawPath 模型提供的工作空间相对路径
     * @return 可安全创建或覆盖的规范化绝对路径
     * @throws ToolExecutionException 路径无效、越界或涉及符号链接时抛出
     */
    public Path resolveForWrite(String rawPath) {
        Path candidate = resolveLexically(rawPath);
        if (Files.isSymbolicLink(candidate)) {
            throw new ToolExecutionException("SYMLINK_WRITE_FORBIDDEN", "Writing through a symbolic link is not allowed");
        }
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            ensureRealPathInsideWorkspace(candidate);
            return candidate;
        }

        Path ancestor = candidate.getParent();
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.getParent();
        }
        if (ancestor == null) {
            throw new ToolExecutionException("PATH_OUTSIDE_WORKSPACE", "Unable to resolve path inside workspace");
        }
        ensureRealPathInsideWorkspace(ancestor);
        return candidate;
    }

    /**
     * 再次确认新建后的目录没有通过链接跳出工作区。
     *
     * @param directory 新建完成的目录
     * @throws ToolExecutionException 真实路径越过工作空间边界时抛出
     */
    public void ensureDirectoryInsideWorkspace(Path directory) {
        ensureRealPathInsideWorkspace(directory);
    }

    /**
     * 将路径转换为适合工具结果展示的工作区相对路径。
     *
     * @param path 待展示路径
     * @return 工作空间内返回正斜杠相对路径，根目录返回 {@code .}，外部路径返回规范化路径
     */
    public String display(Path path) {
        Path root = root();
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(root)) {
            String relative = root.relativize(normalized).toString().replace('\\', '/');
            return relative.isEmpty() ? "." : relative;
        }
        return normalized.toString().replace('\\', '/');
    }

    /**
     * 执行不访问文件系统的第一层词法边界检查。
     *
     * @param rawPath 原始相对路径文本
     * @return 词法规范化后的工作空间内绝对路径
     * @throws ToolExecutionException 路径为空、绝对、语法无效或包含越界片段时抛出
     */
    private Path resolveLexically(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new ToolExecutionException("INVALID_ARGUMENTS", "path must not be blank");
        }
        try {
            Path root = root();
            Path supplied = Path.of(rawPath);
            if (supplied.isAbsolute() || supplied.getRoot() != null) {
                throw new ToolExecutionException("ABSOLUTE_PATH_FORBIDDEN", "path must be relative to the workspace");
            }
            Path candidate = root.resolve(supplied).normalize();
            if (!candidate.startsWith(root)) {
                throw new ToolExecutionException("PATH_OUTSIDE_WORKSPACE", "path must stay inside the workspace");
            }
            return candidate;
        } catch (InvalidPathException exception) {
            throw new ToolExecutionException("INVALID_PATH", "path is not valid");
        }
    }

    /**
     * 使用真实路径执行第二层符号链接边界检查。
     *
     * @param path 必须已经存在的路径
     * @throws ToolExecutionException 路径不可访问或真实位置越过工作空间时抛出
     */
    private void ensureRealPathInsideWorkspace(Path path) {
        try {
            Path realRoot = root().toRealPath();
            Path realPath = path.toRealPath();
            if (!realPath.startsWith(realRoot)) {
                throw new ToolExecutionException("PATH_OUTSIDE_WORKSPACE", "path resolves outside the workspace");
            }
        } catch (IOException exception) {
            throw new ToolExecutionException("PATH_ACCESS_FAILED", "Unable to access path: " + display(path));
        }
    }
}
