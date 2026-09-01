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

class ConversationWorkspaceServiceTest {

    @TempDir
    Path tempDirectory;

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

    private ConversationWorkspaceService service() {
        ConversationWorkspaceService service = new ConversationWorkspaceService(properties());
        service.initialize();
        return service;
    }

    private ConversationWorkspaceProperties properties() {
        return new ConversationWorkspaceProperties(tempDirectory, 20, 1024, 4096);
    }

    private static Conversation conversation(String id, ConversationMode mode) {
        Instant now = Instant.now();
        return new Conversation(id, "Test", mode, now, now);
    }
}
