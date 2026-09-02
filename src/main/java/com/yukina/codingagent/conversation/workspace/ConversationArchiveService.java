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

    /** 下载时忽略的版本库、IDE 配置和可再生依赖/构建目录。 */
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", ".gradle", ".idea", "node_modules", "out", "target"
    );

    /** 定位会话正式项目目录的服务。 */
    private final ConversationWorkspaceService workspaceService;
    /** 防止打包期间项目被同一会话任务并发修改的锁。 */
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

    /**
     * 在已持有会话锁时收集并写入正式项目文件。
     *
     * @param conversation 目标 CODE 会话
     * @param outputStream ZIP 输出目标
     * @throws ConversationWorkspaceException 文件越界或归档写入失败时抛出
     */
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

    /**
     * 递归收集可交付文件，跳过符号链接和可再生目录。
     *
     * @param root 会话正式项目根目录
     * @return 按可移植相对路径排序的文件列表
     * @throws ConversationWorkspaceException 遍历目录失败时抛出
     */
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

    /**
     * 判断目录是否属于下载归档排除范围。
     *
     * @param directory 待判断目录
     * @return 目录名位于排除集合时返回 {@code true}
     */
    private static boolean excluded(Path directory) {
        Path name = directory.getFileName();
        return name != null && EXCLUDED_DIRECTORIES.contains(name.toString().toLowerCase(Locale.ROOT));
    }

    /**
     * 将平台路径转换为 ZIP 规范使用的正斜杠路径。
     *
     * @param path 待转换路径
     * @return 使用正斜杠的可移植路径
     */
    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }
}
