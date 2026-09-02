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

    /** 应用托管的会话、上传暂存和运行数据根目录。 */
    private final Path storageRoot;

    /**
     * 创建会话目录服务。
     *
     * @param properties 会话目录配置
     */
    public ConversationWorkspaceService(ConversationWorkspaceProperties properties) {
        this.storageRoot = properties.storageRoot().toAbsolutePath().normalize();
    }

    /**
     * 创建正式会话目录和内部上传暂存目录。
     *
     * @throws ConversationWorkspaceException 托管根目录无法创建时抛出
     */
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

    /**
     * 返回上传请求使用的内部暂存根目录。
     *
     * @return 上传请求使用的暂存根目录
     */
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

    /**
     * 将不可信会话 ID 映射到直接受托管根目录约束的目录。
     *
     * @param conversationId 待映射会话 ID
     * @return 会话内部根目录，不包含 workspace 后缀
     * @throws IllegalArgumentException 会话 ID 为空或包含路径分隔符时抛出
     */
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

    /**
     * 计算会话正式项目目录的规范化路径。
     *
     * @param conversationId 已验证或待验证的会话 ID
     * @return 以 workspace 结尾的托管项目路径
     */
    private Path workspacePath(String conversationId) {
        return conversationPath(conversationId).resolve("workspace").normalize();
    }
}
