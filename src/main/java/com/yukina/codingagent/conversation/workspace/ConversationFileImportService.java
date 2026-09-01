package com.yukina.codingagent.conversation.workspace;

import com.yukina.codingagent.conversation.exception.ConversationWorkspaceException;
import com.yukina.codingagent.conversation.model.Conversation;
import com.yukina.codingagent.conversation.model.ConversationImportResult;
import com.yukina.codingagent.conversation.service.ConversationLockManager;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 在会话锁内校验、暂存并提交浏览器上传的项目文件。
 */
@Service
public class ConversationFileImportService {

    private final ConversationWorkspaceService workspaceService;
    private final ConversationWorkspaceProperties properties;
    private final ConversationLockManager lockManager;

    /**
     * 创建会话文件导入服务。
     *
     * @param workspaceService 会话目录服务
     * @param properties 文件数量和大小限制
     * @param lockManager 会话串行锁
     */
    public ConversationFileImportService(
            ConversationWorkspaceService workspaceService,
            ConversationWorkspaceProperties properties,
            ConversationLockManager lockManager
    ) {
        this.workspaceService = workspaceService;
        this.properties = properties;
        this.lockManager = lockManager;
    }

    /**
     * 导入一批文件；目录上传时会去掉唯一公共顶层文件夹。
     *
     * @param conversation 目标 CODE 会话
     * @param files 浏览器上传文件
     * @param relativePaths 与文件一一对应的相对路径
     * @return 导入数量、字节数和最终相对路径
     */
    public ConversationImportResult importFiles(
            Conversation conversation,
            List<MultipartFile> files,
            List<String> relativePaths
    ) {
        return lockManager.withLock(
                conversation.id(),
                () -> importLocked(conversation, files, relativePaths)
        );
    }

    /**
     * 将 UTF-8 源码作为单文件导入。
     *
     * @param conversation 目标 CODE 会话
     * @param relativePath 项目相对路径
     * @param content 源码内容
     * @return 单文件导入结果
     */
    public ConversationImportResult importCode(
            Conversation conversation,
            String relativePath,
            String content
    ) {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > properties.maxFileBytes() || bytes.length > properties.maxTotalBytes()) {
            throw new IllegalArgumentException("Pasted code exceeds the upload limit");
        }
        return lockManager.withLock(
                conversation.id(),
                () -> commit(conversation, List.of(new SourceFile(relativePath, bytes)))
        );
    }

    private ConversationImportResult importLocked(
            Conversation conversation,
            List<MultipartFile> files,
            List<String> relativePaths
    ) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("At least one file is required");
        }
        if (files.size() > properties.maxFiles()) {
            throw new IllegalArgumentException("Too many files in one upload");
        }
        if (relativePaths == null || files.size() != relativePaths.size()) {
            throw new IllegalArgumentException("Each uploaded file must have one relative path");
        }

        List<String> normalizedPaths = normalizeUploadRoot(relativePaths);
        List<SourceFile> sources = new ArrayList<>();
        long totalBytes = 0;
        for (int index = 0; index < files.size(); index++) {
            MultipartFile file = files.get(index);
            if (file == null) {
                throw new IllegalArgumentException("Uploaded file is invalid");
            }
            if (file.getSize() > properties.maxFileBytes()) {
                throw new IllegalArgumentException("Uploaded file exceeds the per-file limit");
            }
            totalBytes += file.getSize();
            if (totalBytes > properties.maxTotalBytes()) {
                throw new IllegalArgumentException("Uploaded files exceed the total size limit");
            }
            try {
                sources.add(new SourceFile(normalizedPaths.get(index), file.getBytes()));
            } catch (IOException exception) {
                throw new IllegalArgumentException("Unable to read uploaded file: " + normalizedPaths.get(index));
            }
        }
        return commit(conversation, sources);
    }

    private ConversationImportResult commit(Conversation conversation, List<SourceFile> sources) {
        Path root = workspaceService.root(conversation);
        Set<Path> targets = new HashSet<>();
        List<PendingFile> pending = new ArrayList<>();
        for (SourceFile source : sources) {
            String relativePath = normalizeRelativePath(source.relativePath());
            Path target = root.resolve(relativePath).normalize();
            if (!target.startsWith(root) || !targets.add(target)) {
                throw new IllegalArgumentException("Uploaded paths must be unique and remain inside the project");
            }
            ensureNoSymbolicLinks(root, target);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new ConversationWorkspaceException("File already exists: " + relativePath);
            }
            pending.add(new PendingFile(relativePath, target, source.content()));
        }

        Path staging = createStagingDirectory(conversation.id());
        List<Path> createdTargets = new ArrayList<>();
        try {
            for (PendingFile file : pending) {
                Path staged = staging.resolve(file.relativePath()).normalize();
                Files.createDirectories(staged.getParent());
                Files.write(staged, file.content());
            }
            for (PendingFile file : pending) {
                Path staged = staging.resolve(file.relativePath()).normalize();
                Files.createDirectories(file.target().getParent());
                move(staged, file.target());
                createdTargets.add(file.target());
            }
        } catch (IOException exception) {
            rollback(createdTargets);
            throw new ConversationWorkspaceException("Unable to import project files");
        } finally {
            deleteTreeQuietly(staging);
        }

        long bytes = pending.stream().mapToLong(file -> file.content().length).sum();
        return new ConversationImportResult(
                conversation.id(),
                pending.size(),
                bytes,
                pending.stream().map(PendingFile::relativePath).toList()
        );
    }

    private Path createStagingDirectory(String conversationId) {
        try {
            return Files.createTempDirectory(
                    workspaceService.importsRoot(),
                    conversationId + "-" + UUID.randomUUID() + "-"
            );
        } catch (IOException exception) {
            throw new ConversationWorkspaceException("Unable to create upload staging directory");
        }
    }

    private static List<String> normalizeUploadRoot(List<String> paths) {
        List<Path> normalized = paths.stream().map(ConversationFileImportService::parseRelativePath).toList();
        if (normalized.isEmpty() || normalized.stream().anyMatch(path -> path.getNameCount() < 2)) {
            return normalized.stream().map(ConversationFileImportService::portable).toList();
        }
        String first = normalized.getFirst().getName(0).toString();
        if (normalized.stream().anyMatch(path -> !path.getName(0).toString().equals(first))) {
            return normalized.stream().map(ConversationFileImportService::portable).toList();
        }
        return normalized.stream().map(path -> portable(path.subpath(1, path.getNameCount()))).toList();
    }

    private static String normalizeRelativePath(String rawPath) {
        return portable(parseRelativePath(rawPath));
    }

    private static Path parseRelativePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("Uploaded path must not be blank");
        }
        try {
            Path path = Path.of(rawPath.trim().replace('\\', '/')).normalize();
            if (path.isAbsolute() || path.getNameCount() == 0 || path.startsWith("..")) {
                throw new IllegalArgumentException("Uploaded path must be relative to the project");
            }
            return path;
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("Uploaded path is invalid");
        }
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void ensureNoSymbolicLinks(Path root, Path target) {
        Path cursor = root;
        for (Path segment : root.relativize(target)) {
            cursor = cursor.resolve(segment);
            if (Files.isSymbolicLink(cursor)) {
                throw new IllegalArgumentException("Uploaded path must not contain symbolic links");
            }
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static void rollback(List<Path> paths) {
        for (int index = paths.size() - 1; index >= 0; index--) {
            try {
                Files.deleteIfExists(paths.get(index));
            } catch (IOException ignored) {
                // 保留最初的上传失败语义。
            }
        }
    }

    private static void deleteTreeQuietly(Path root) {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // 暂存目录清理失败不覆盖原始导入结果。
        }
    }

    private record SourceFile(String relativePath, byte[] content) {
    }

    private record PendingFile(String relativePath, Path target, byte[] content) {
    }
}
