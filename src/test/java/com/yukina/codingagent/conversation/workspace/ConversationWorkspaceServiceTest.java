package com.yukina.codingagent.conversation.workspace;

import com.yukina.codingagent.conversation.model.Conversation;
import com.yukina.codingagent.conversation.model.ConversationMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 CODE 会话目录的创建、隔离和删除生命周期。 */
class ConversationWorkspaceServiceTest {

    /** 为每个用例提供隔离的会话数据根目录。 */
    @TempDir
    Path tempDirectory;

    /** 验证仅 CODE 会话拥有工作目录，删除会话时目录一并清理。 */
    @Test
    void createsWorkspaceOnlyForCodeConversationAndDeletesIt() throws Exception {
        ConversationWorkspaceService service = service();
        Conversation code = conversation("code-1", ConversationMode.CODE);
        Path root = service.root(code);

        assertTrue(root.endsWith(Path.of("conversations", "code-1", "workspace")));
        Files.writeString(root.resolve("Main.java"), "class Main {}\n");
        assertTrue(service.hasFiles(code));

        service.delete(code);
        assertFalse(Files.exists(root));
        assertThrows(IllegalArgumentException.class, () -> service.root(conversation("chat-1", ConversationMode.CHAT)));
    }

    /** @return 已完成目录初始化的测试服务 */
    private ConversationWorkspaceService service() {
        ConversationWorkspaceService service = new ConversationWorkspaceService(properties());
        service.initialize();
        return service;
    }

    /** @return 使用临时目录和较小边界的测试配置 */
    private ConversationWorkspaceProperties properties() {
        return new ConversationWorkspaceProperties(tempDirectory, 20, 1024, 4096);
    }

    /**
     * 创建指定模式的测试会话。
     *
     * @param id 会话 ID
     * @param mode CHAT 或 CODE 模式
     * @return 使用当前时间戳的会话
     */
    private static Conversation conversation(String id, ConversationMode mode) {
        Instant now = Instant.now();
        return new Conversation(id, "Test", mode, now, now);
    }
}
