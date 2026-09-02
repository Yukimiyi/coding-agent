package com.yukina.codingagent.conversation.workspace;

import com.yukina.codingagent.conversation.exception.ConversationWorkspaceException;
import com.yukina.codingagent.conversation.model.Conversation;
import com.yukina.codingagent.conversation.model.ConversationMode;
import com.yukina.codingagent.conversation.service.ConversationLockManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证上传目录归一化和文件冲突保护。 */
class ConversationFileImportServiceTest {

    /** 为每个用例提供隔离的会话数据根目录。 */
    @TempDir
    Path tempDirectory;

    /** 管理当前测试会话的工作目录。 */
    private ConversationWorkspaceService workspaceService;
    /** 执行批量上传和代码粘贴导入。 */
    private ConversationFileImportService importService;
    /** 接收测试文件的 CODE 会话。 */
    private Conversation conversation;

    /** 创建隔离工作目录、导入服务和 CODE 会话。 */
    @BeforeEach
    void setUp() {
        ConversationWorkspaceProperties properties = new ConversationWorkspaceProperties(
                tempDirectory, 20, 1024, 4096
        );
        workspaceService = new ConversationWorkspaceService(properties);
        workspaceService.initialize();
        importService = new ConversationFileImportService(
                workspaceService,
                properties,
                new ConversationLockManager()
        );
        Instant now = Instant.now();
        conversation = new Conversation("code-1", "Project", ConversationMode.CODE, now, now);
    }

    /** 验证浏览器上传的共同顶层文件夹会被剥离，项目直接进入工作目录。 */
    @Test
    void stripsSingleUploadedFolderAndKeepsOneProjectRoot() throws Exception {
        var result = importService.importFiles(
                conversation,
                List.of(
                        new MockMultipartFile("files", "pom.xml", "text/xml", "<project/>".getBytes()),
                        new MockMultipartFile("files", "App.java", "text/plain", "class App {}".getBytes())
                ),
                List.of("sample/pom.xml", "sample/src/App.java")
        );

        Path root = workspaceService.root(conversation);
        assertEquals(List.of("pom.xml", "src/App.java"), result.paths());
        assertTrue(Files.exists(root.resolve("pom.xml")));
        assertFalse(Files.exists(root.resolve("sample")));
    }

    /** 验证再次导入同名文件会失败且不会覆盖已有代码。 */
    @Test
    void rejectsConflictsWithoutOverwritingExistingFile() {
        importService.importCode(conversation, "Main.java", "class Main {}\n");

        assertThrows(
                ConversationWorkspaceException.class,
                () -> importService.importCode(conversation, "Main.java", "class Changed {}\n")
        );
    }
}
