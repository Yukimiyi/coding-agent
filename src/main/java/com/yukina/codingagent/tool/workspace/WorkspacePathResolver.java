package com.yukina.codingagent.tool.workspace;

import com.yukina.codingagent.tool.ToolExecutionException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;

@Component
public class WorkspacePathResolver {

    private final Path root;
    private final Path realRoot;

    public WorkspacePathResolver(WorkspaceProperties properties) {
        this.root = properties.root().toAbsolutePath().normalize();
        try {
            if (!Files.isDirectory(root)) {
                throw new IllegalStateException("Workspace root is not a directory: " + root);
            }
            this.realRoot = root.toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to initialize workspace root: " + root, exception);
        }
    }

    public Path root() {
        return root;
    }

    public Path resolveExisting(String rawPath) {
        Path candidate = resolveLexically(rawPath);
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new ToolExecutionException("PATH_NOT_FOUND", "Path does not exist: " + display(candidate));
        }
        ensureRealPathInsideWorkspace(candidate);
        return candidate;
    }

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

    public void ensureDirectoryInsideWorkspace(Path directory) {
        ensureRealPathInsideWorkspace(directory);
    }

    public String display(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(root)) {
            String relative = root.relativize(normalized).toString().replace('\\', '/');
            return relative.isEmpty() ? "." : relative;
        }
        return normalized.toString().replace('\\', '/');
    }

    private Path resolveLexically(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new ToolExecutionException("INVALID_ARGUMENTS", "path must not be blank");
        }
        try {
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

    private void ensureRealPathInsideWorkspace(Path path) {
        try {
            Path realPath = path.toRealPath();
            if (!realPath.startsWith(realRoot)) {
                throw new ToolExecutionException("PATH_OUTSIDE_WORKSPACE", "path resolves outside the workspace");
            }
        } catch (IOException exception) {
            throw new ToolExecutionException("PATH_ACCESS_FAILED", "Unable to access path: " + display(path));
        }
    }
}
