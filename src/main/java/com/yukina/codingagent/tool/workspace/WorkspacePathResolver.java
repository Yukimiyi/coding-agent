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

    /** 创建使用运行范围工作空间上下文的路径解析器。 */
    @Autowired
    public WorkspacePathResolver(WorkspaceExecutionContext executionContext) {
        this.executionContext = executionContext;
    }

    /** 创建使用固定默认目录的独立解析器，供非 Spring 场景使用。 */
    public WorkspacePathResolver(WorkspaceProperties properties) {
        this(new WorkspaceExecutionContext(properties));
    }

    /** 返回当前运行绑定的工作区根目录。 */
    public Path root() {
        return executionContext.root();
    }

    /** 解析并校验一个必须存在的工作区相对路径。 */
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

    /** 再次确认新建后的目录没有通过链接跳出工作区。 */
    public void ensureDirectoryInsideWorkspace(Path directory) {
        ensureRealPathInsideWorkspace(directory);
    }

    /** 将路径转换为适合工具结果展示的工作区相对路径。 */
    public String display(Path path) {
        Path root = root();
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(root)) {
            String relative = root.relativize(normalized).toString().replace('\\', '/');
            return relative.isEmpty() ? "." : relative;
        }
        return normalized.toString().replace('\\', '/');
    }

    /** 执行不访问文件系统的第一层词法边界检查。 */
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

    /** 使用真实路径执行第二层符号链接边界检查。 */
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
