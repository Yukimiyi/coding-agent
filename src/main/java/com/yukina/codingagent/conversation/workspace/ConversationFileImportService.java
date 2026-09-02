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

    /** 定位会话正式目录和上传暂存根目录的服务。 */
    private final ConversationWorkspaceService workspaceService;
    /** 单文件、文件总量和总字节数安全边界。 */
    private final ConversationWorkspaceProperties properties;
    /** 防止导入与同一会话 Agent 文件操作并发执行的锁。 */
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

    /**
     * 在已持有会话锁时读取并初步校验浏览器上传内容。
     *
     * @param conversation 目标 CODE 会话
     * @param files 上传文件列表
     * @param relativePaths 与上传文件顺序对应的项目相对路径
     * @return 校验并提交后的导入摘要
     * @throws IllegalArgumentException 文件数量、大小、路径或读取结果不合法时抛出
     */
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

    /**
     * 先将所有文件写入暂存目录，再以移动方式提交到正式工作区。
     *
     * @param conversation 目标 CODE 会话
     * @param sources 已加载到内存的源文件
     * @return 成功提交的文件数量、总字节数和路径
     * @throws ConversationWorkspaceException 目标冲突或文件系统操作失败时抛出
     */
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

    /**
     * 为一次导入创建会话关联的唯一暂存目录。
     *
     * @param conversationId 目标会话 ID
     * @return 位于内部 imports 根目录下的新目录
     * @throws ConversationWorkspaceException 暂存目录无法创建时抛出
     */
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

    /**
     * 当所有上传路径共享唯一顶层文件夹时去除该层目录。
     *
     * @param paths 浏览器提供的原始相对路径
     * @return 保持文件顺序的规范化项目相对路径
     */
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

    /**
     * 校验并转换单个上传相对路径。
     *
     * @param rawPath 浏览器或粘贴请求提供的路径
     * @return 使用正斜杠且不越出项目根目录的路径
     */
    private static String normalizeRelativePath(String rawPath) {
        return portable(parseRelativePath(rawPath));
    }

    /**
     * 将不可信文本解析为受限相对路径对象。
     *
     * @param rawPath 待解析路径文本
     * @return 规范化相对路径
     * @throws IllegalArgumentException 路径为空、绝对、越界或语法无效时抛出
     */
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

    /**
     * 将平台路径转换为接口使用的正斜杠形式。
     *
     * @param path 待转换路径
     * @return 使用正斜杠的路径文本
     */
    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    /**
     * 检查目标路径各层级均不是符号链接，防止间接逃逸工作区。
     *
     * @param root 已验证的正式项目根目录
     * @param target 待创建文件目标
     * @throws IllegalArgumentException 任一路径层级为符号链接时抛出
     */
    private static void ensureNoSymbolicLinks(Path root, Path target) {
        Path cursor = root;
        for (Path segment : root.relativize(target)) {
            cursor = cursor.resolve(segment);
            if (Files.isSymbolicLink(cursor)) {
                throw new IllegalArgumentException("Uploaded path must not contain symbolic links");
            }
        }
    }

    /**
     * 优先原子移动暂存文件，不支持时回退为普通同文件系统移动。
     *
     * @param source 暂存文件
     * @param target 正式项目目标文件
     * @throws IOException 两种移动方式均失败时抛出
     */
    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    /**
     * 按提交逆序尽力删除本次已经创建的正式文件。
     *
     * @param paths 已成功移动到正式目录的文件
     */
    private static void rollback(List<Path> paths) {
        for (int index = paths.size() - 1; index >= 0; index--) {
            try {
                Files.deleteIfExists(paths.get(index));
            } catch (IOException ignored) {
                // 保留最初的上传失败语义。
            }
        }
    }

    /**
     * 尽力清理内部暂存目录，不覆盖原始导入结果或异常。
     *
     * @param root 待删除暂存目录
     */
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

    /**
     * 尚未完成路径规范化的内存源文件。
     *
     * @param relativePath 调用方提供的相对路径
     * @param content 文件原始字节
     */
    private record SourceFile(String relativePath, byte[] content) {
    }

    /**
     * 已通过路径和冲突检查、等待暂存提交的文件。
     *
     * @param relativePath 规范化项目相对路径
     * @param target 正式工作区目标路径
     * @param content 文件原始字节
     */
    private record PendingFile(String relativePath, Path target, byte[] content) {
    }
}
