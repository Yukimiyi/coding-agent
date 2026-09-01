package com.yukina.codingagent.conversation.workspace;

import com.yukina.codingagent.conversation.exception.ConversationWorkspaceException;
import com.yukina.codingagent.conversation.model.Conversation;
import com.yukina.codingagent.conversation.model.ConversationMode;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * 将每个 CODE 会话映射到唯一的应用托管项目目录。
 */
@Service
public class ConversationWorkspaceService {

    private final Path storageRoot;

    /**
     * 创建会话目录服务。
     *
     * @param properties 会话目录配置
     */
    public ConversationWorkspaceService(ConversationWorkspaceProperties properties) {
        this.storageRoot = properties.storageRoot().toAbsolutePath().normalize();
    }

    /** @throws ConversationWorkspaceException 托管根目录无法创建时抛出 */
    @PostConstruct
    public void initialize() {
        try {
            Files.createDirectories(storageRoot.resolve("conversations"));
            Files.createDirectories(storageRoot.resolve("imports"));
        } catch (IOException exception) {
            throw new ConversationWorkspaceException("Unable to initialize conversation storage");
        }
    }

    /**
     * 返回 CODE 会话的正式项目目录，不存在时自动创建。
     *
     * @param conversation 目标会话
     * @return 已存在的真实项目目录
     * @throws IllegalArgumentException CHAT 会话没有项目目录时抛出
     * @throws ConversationWorkspaceException 目录无法创建或越过托管边界时抛出
     */
    public Path root(Conversation conversation) {
        requireCode(conversation);
        Path root = workspacePath(conversation.id());
        try {
            Files.createDirectories(root);
            Path realRoot = root.toRealPath();
            if (!realRoot.startsWith(storageRoot.toRealPath())) {
                throw new ConversationWorkspaceException("Conversation workspace is outside managed storage");
            }
            return realRoot;
        } catch (IOException exception) {
            throw new ConversationWorkspaceException("Unable to access conversation workspace");
        }
    }

    /**
     * 判断 CODE 会话目录中是否已经包含可交付文件。
     *
     * @param conversation 目标会话
     * @return 至少存在一个非符号链接普通文件时返回 {@code true}
     */
    public boolean hasFiles(Conversation conversation) {
        if (conversation.mode() != ConversationMode.CODE) {
            return false;
        }
        Path root = workspacePath(conversation.id());
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (var paths = Files.walk(root)) {
            return paths.anyMatch(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(path));
        } catch (IOException exception) {
            throw new ConversationWorkspaceException("Unable to inspect conversation workspace");
        }
    }

    /**
     * 删除会话时回收其整个托管目录。
     *
     * @param conversation 待删除会话
     * @throws ConversationWorkspaceException 目录清理失败时抛出
     */
    public void delete(Conversation conversation) {
        if (conversation.mode() != ConversationMode.CODE) {
            return;
        }
        Path conversationRoot = conversationPath(conversation.id());
        if (!Files.exists(conversationRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(conversationRoot)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            throw new ConversationWorkspaceException("Unable to delete conversation workspace");
        }
    }

    /** @return 上传请求使用的暂存根目录 */
    Path importsRoot() {
        return storageRoot.resolve("imports");
    }

    /**
     * 拒绝对 CHAT 会话执行项目文件操作。
     *
     * @param conversation 待校验会话
     */
    public static void requireCode(Conversation conversation) {
        if (conversation == null || conversation.mode() != ConversationMode.CODE) {
            throw new IllegalArgumentException("Project files are available only in CODE conversations");
        }
    }

    private Path conversationPath(String conversationId) {
        if (conversationId == null || conversationId.isBlank()
                || conversationId.contains("/") || conversationId.contains("\\")) {
            throw new IllegalArgumentException("conversationId is invalid");
        }
        Path path = storageRoot.resolve("conversations").resolve(conversationId).normalize();
        if (!path.getParent().equals(storageRoot.resolve("conversations"))) {
            throw new IllegalArgumentException("conversationId is invalid");
        }
        return path;
    }

    private Path workspacePath(String conversationId) {
        return conversationPath(conversationId).resolve("workspace").normalize();
    }
}
