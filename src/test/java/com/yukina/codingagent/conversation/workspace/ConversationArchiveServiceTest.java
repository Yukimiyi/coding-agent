package com.yukina.codingagent.conversation.workspace;

import com.yukina.codingagent.conversation.model.Conversation;
import com.yukina.codingagent.conversation.model.ConversationMode;
import com.yukina.codingagent.conversation.service.ConversationLockManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConversationArchiveServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void archivesProjectFilesAndSkipsDependencyCaches() throws Exception {
        ConversationWorkspaceService workspaceService = new ConversationWorkspaceService(
                new ConversationWorkspaceProperties(tempDirectory, 20, 1024, 4096)
        );
        workspaceService.initialize();
        Conversation conversation = new Conversation(
                "code-1", "Calculator", ConversationMode.CODE, Instant.now(), Instant.now()
        );
        Path root = workspaceService.root(conversation);
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Main.java"), "class Main {}\n");
        Files.createDirectories(root.resolve("node_modules/pkg"));
        Files.writeString(root.resolve("node_modules/pkg/index.js"), "ignored");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new ConversationArchiveService(workspaceService, new ConversationLockManager())
                .write(conversation, output);

        List<String> entries = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.add(entry.getName());
            }
        }
        assertEquals(List.of("src/Main.java"), entries);
    }
}
