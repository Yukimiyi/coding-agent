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

class ConversationFileImportServiceTest {

    @TempDir
    Path tempDirectory;

    private ConversationWorkspaceService workspaceService;
    private ConversationFileImportService importService;
    private Conversation conversation;

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

    @Test
    void rejectsConflictsWithoutOverwritingExistingFile() {
        importService.importCode(conversation, "Main.java", "class Main {}\n");

        assertThrows(
                ConversationWorkspaceException.class,
                () -> importService.importCode(conversation, "Main.java", "class Changed {}\n")
        );
    }
}
