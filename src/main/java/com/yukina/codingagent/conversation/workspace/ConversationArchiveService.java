package com.yukina.codingagent.conversation.workspace;

import com.yukina.codingagent.conversation.exception.ConversationWorkspaceException;
import com.yukina.codingagent.conversation.model.Conversation;
import com.yukina.codingagent.conversation.service.ConversationLockManager;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 将 CODE 会话的正式项目目录打包为可下载 ZIP。
 */
@Service
public class ConversationArchiveService {

    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", ".gradle", ".idea", "node_modules", "out", "target"
    );

    private final ConversationWorkspaceService workspaceService;
    private final ConversationLockManager lockManager;

    /**
     * 创建会话项目归档服务。
     *
     * @param workspaceService 会话目录服务
     * @param lockManager 会话串行锁
     */
    public ConversationArchiveService(
            ConversationWorkspaceService workspaceService,
            ConversationLockManager lockManager
    ) {
        this.workspaceService = workspaceService;
        this.lockManager = lockManager;
    }

    /**
     * 在会话锁内将当前完整项目写入 ZIP。
     *
     * @param conversation 目标 CODE 会话
     * @param outputStream HTTP 响应输出流
     */
    public void write(Conversation conversation, OutputStream outputStream) {
        lockManager.withLock(conversation.id(), () -> {
            writeLocked(conversation, outputStream);
            return null;
        });
    }

    /**
     * 生成安全的下载文件名。
     *
     * @param conversation 目标会话
     * @return 以 {@code .zip} 结尾的文件名
     */
    public String fileName(Conversation conversation) {
        String safeName = conversation.title()
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .strip();
        return (safeName.isBlank() ? "project" : safeName) + ".zip";
    }

    private void writeLocked(Conversation conversation, OutputStream outputStream) {
        Path root = workspaceService.root(conversation);
        List<Path> files = collectFiles(root);
        try (ZipOutputStream zip = new ZipOutputStream(outputStream)) {
            for (Path file : files) {
                Path realFile = file.toRealPath();
                if (!realFile.startsWith(root) || Files.isSymbolicLink(file)) {
                    throw new ConversationWorkspaceException("Project file is outside the conversation workspace");
                }
                ZipEntry entry = new ZipEntry(portable(root.relativize(file)));
                entry.setLastModifiedTime(Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS));
                zip.putNextEntry(entry);
                Files.copy(realFile, zip);
                zip.closeEntry();
            }
            zip.finish();
        } catch (IOException exception) {
            throw new ConversationWorkspaceException("Unable to create project archive");
        }
    }

    private static List<Path> collectFiles(Path root) {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (!directory.equals(root) && excluded(directory)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile() && !Files.isSymbolicLink(file)) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            files.sort((left, right) -> portable(root.relativize(left))
                    .compareTo(portable(root.relativize(right))));
            return files;
        } catch (IOException exception) {
            throw new ConversationWorkspaceException("Unable to read project files for download");
        }
    }

    private static boolean excluded(Path directory) {
        Path name = directory.getFileName();
        return name != null && EXCLUDED_DIRECTORIES.contains(name.toString().toLowerCase(Locale.ROOT));
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }
}
