package com.yukina.codingagent.workspace.service;

import com.yukina.codingagent.workspace.WorkspaceImportProperties;
import com.yukina.codingagent.workspace.exception.WorkspaceConflictException;
import com.yukina.codingagent.workspace.model.Workspace;
import com.yukina.codingagent.workspace.model.WorkspaceImportResult;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 将浏览器上传的文件或粘贴的源码安全写入指定托管工作空间。
 */
@Service
public class WorkspaceImportService {

    private final WorkspaceService workspaceService;
    private final WorkspaceImportProperties properties;

    /** 创建工作空间内容导入服务。 */
    public WorkspaceImportService(
            WorkspaceService workspaceService,
            WorkspaceImportProperties properties
    ) {
        this.workspaceService = workspaceService;
        this.properties = properties;
    }

    /**
     * 导入一批文件并保留浏览器提供的相对目录结构。
     * 任意文件失败时会回滚本次请求已经创建的文件。
     */
    public synchronized WorkspaceImportResult importFiles(
            String workspaceId,
            List<MultipartFile> files,
            List<String> relativePaths
    ) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("At least one file is required");
        }
        if (files.size() > properties.maxFiles()) {
            throw new IllegalArgumentException("Too many files in one import");
        }
        if (relativePaths == null || files.size() != relativePaths.size()) {
            throw new IllegalArgumentException("Each uploaded file must have one relative path");
        }

        Workspace workspace = workspaceService.get(workspaceId);
        Path root = workspaceService.rootPath(workspace).toAbsolutePath().normalize();
        List<PendingFile> pendingFiles = validateFiles(root, files, relativePaths);
        List<Path> createdFiles = new ArrayList<>();
        List<Path> createdDirectories = new ArrayList<>();
        try {
            for (PendingFile pending : pendingFiles) {
                createParentDirectories(root, pending.target().getParent(), createdDirectories);
                Files.write(
                        pending.target(),
                        pending.content(),
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE
                );
                createdFiles.add(pending.target());
            }
        } catch (IOException exception) {
            rollback(createdFiles, createdDirectories);
            throw new WorkspaceConflictException("Unable to import workspace files");
        }

        long totalBytes = pendingFiles.stream().mapToLong(file -> file.content().length).sum();
        List<String> paths = pendingFiles.stream().map(PendingFile::relativePath).toList();
        return new WorkspaceImportResult(workspaceId, pendingFiles.size(), totalBytes, paths);
    }

    /** 将一段 UTF-8 源码写入指定的工作空间相对路径。 */
    public WorkspaceImportResult importCode(String workspaceId, String relativePath, String content) {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > properties.maxFileBytes() || bytes.length > properties.maxTotalBytes()) {
            throw new IllegalArgumentException("Pasted code exceeds the upload limit");
        }
        MultipartFile source = new InMemorySourceFile(relativePath, bytes);
        return importFiles(workspaceId, List.of(source), List.of(relativePath));
    }

    /** 先完成全部路径、冲突和大小校验，避免产生部分写入。 */
    private List<PendingFile> validateFiles(
            Path root,
            List<MultipartFile> files,
            List<String> relativePaths
    ) {
        List<PendingFile> pendingFiles = new ArrayList<>();
        Set<Path> targets = new HashSet<>();
        long totalBytes = 0;
        for (int index = 0; index < files.size(); index++) {
            MultipartFile file = files.get(index);
            if (file == null || file.isEmpty() && file.getSize() == 0 && relativePaths.get(index).isBlank()) {
                throw new IllegalArgumentException("Uploaded file is invalid");
            }
            if (file.getSize() > properties.maxFileBytes()) {
                throw new IllegalArgumentException("Uploaded file exceeds the per-file limit");
            }
            totalBytes += file.getSize();
            if (totalBytes > properties.maxTotalBytes()) {
                throw new IllegalArgumentException("Uploaded files exceed the total size limit");
            }
            String relativePath = normalizeRelativePath(relativePaths.get(index));
            Path target = root.resolve(relativePath).normalize();
            if (!target.startsWith(root) || !targets.add(target)) {
                throw new IllegalArgumentException("Uploaded paths must be unique and remain inside the workspace");
            }
            ensureNoSymbolicLinks(root, target);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new WorkspaceConflictException("File already exists: " + relativePath);
            }
            try {
                pendingFiles.add(new PendingFile(relativePath, target, file.getBytes()));
            } catch (IOException exception) {
                throw new IllegalArgumentException("Unable to read uploaded file: " + relativePath);
            }
        }
        return pendingFiles;
    }

    /** 拒绝经由既有符号链接跳出工作空间的上传路径。 */
    private static void ensureNoSymbolicLinks(Path root, Path target) {
        Path cursor = root;
        for (Path segment : root.relativize(target)) {
            cursor = cursor.resolve(segment);
            if (Files.isSymbolicLink(cursor)) {
                throw new IllegalArgumentException("Uploaded path must not contain symbolic links");
            }
        }
    }

    /** 规范化并校验浏览器传入的工作空间相对路径。 */
    private static String normalizeRelativePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("Uploaded path must not be blank");
        }
        try {
            String portablePath = rawPath.trim().replace('\\', '/');
            Path path = Path.of(portablePath).normalize();
            if (path.isAbsolute() || path.getNameCount() == 0 || path.startsWith("..")) {
                throw new IllegalArgumentException("Uploaded path must be relative to the workspace");
            }
            return path.toString().replace('\\', '/');
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("Uploaded path is invalid");
        }
    }

    /** 逐层创建父目录，并记录本次创建的目录以支持失败回滚。 */
    private static void createParentDirectories(Path root, Path parent, List<Path> createdDirectories)
            throws IOException {
        if (parent == null || parent.equals(root)) {
            return;
        }
        List<Path> missing = new ArrayList<>();
        Path cursor = parent;
        while (cursor != null && !cursor.equals(root) && Files.notExists(cursor)) {
            missing.add(cursor);
            cursor = cursor.getParent();
        }
        for (int index = missing.size() - 1; index >= 0; index--) {
            Path directory = missing.get(index);
            Files.createDirectory(directory);
            createdDirectories.add(directory);
        }
    }

    /** 仅删除当前请求创建的文件和空目录。 */
    private static void rollback(List<Path> files, List<Path> directories) {
        for (int index = files.size() - 1; index >= 0; index--) {
            deleteQuietly(files.get(index));
        }
        for (int index = directories.size() - 1; index >= 0; index--) {
            deleteQuietly(directories.get(index));
        }
    }

    /** 尽力删除一个本次请求创建的路径。 */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 回滚为尽力操作，保留原始导入失败语义。
        }
    }

    /** 已通过校验、等待写入的文件。 */
    private record PendingFile(String relativePath, Path target, byte[] content) {
    }

    /** 将粘贴源码适配为 MultipartFile，复用同一套导入校验与写入流程。 */
    private record InMemorySourceFile(String name, byte[] content) implements MultipartFile {

        /** 返回表单字段名。 */
        @Override
        public String getName() {
            return "file";
        }

        /** 返回用户提供的相对文件名。 */
        @Override
        public String getOriginalFilename() {
            return name;
        }

        /** 粘贴源码按纯文本处理。 */
        @Override
        public String getContentType() {
            return "text/plain; charset=utf-8";
        }

        /** 判断粘贴内容是否为空。 */
        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        /** 返回 UTF-8 字节长度。 */
        @Override
        public long getSize() {
            return content.length;
        }

        /** 返回源码字节副本。 */
        @Override
        public byte[] getBytes() {
            return content.clone();
        }

        /** 打开源码输入流。 */
        @Override
        public java.io.InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(content);
        }

        /** 将源码传输到目标文件。 */
        @Override
        public void transferTo(java.io.File destination) throws IOException {
            Files.write(destination.toPath(), content);
        }

        /** 将源码传输到目标路径。 */
        @Override
        public void transferTo(Path destination) throws IOException {
            Files.write(destination, content);
        }
    }
}
