package com.yukina.codingagent.workspace.service;

import com.yukina.codingagent.conversation.repository.ConversationRepository;
import com.yukina.codingagent.workspace.WorkspaceRegistryProperties;
import com.yukina.codingagent.workspace.model.Workspace;
import com.yukina.codingagent.workspace.model.WorkspaceType;
import com.yukina.codingagent.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证项目工作空间创建、无默认项目和旧目录迁移。 */
class WorkspaceServiceTest {

    @TempDir
    Path temporaryRoot;

    private EmbeddedDatabase database;
    private WorkspaceService workspaceService;
    private WorkspaceRepository workspaceRepository;
    private ConversationRepository conversationRepository;
    private Path storageRoot;

    /** 创建真实仓储和托管项目目录。 */
    @BeforeEach
    void setUp() throws Exception {
        database = new EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .addScript("schema.sql")
                .build();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(database);
        workspaceRepository = new WorkspaceRepository(jdbcTemplate);
        conversationRepository = new ConversationRepository(jdbcTemplate);
        storageRoot = Files.createDirectory(temporaryRoot.resolve("projects"));
        workspaceService = new WorkspaceService(
                workspaceRepository,
                new WorkspaceRegistryProperties(10, storageRoot)
        );
    }

    /** 关闭当前用例数据库。 */
    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    /** 验证初始化只创建存储容器，不自动生成默认项目。 */
    @Test
    void initializesWithoutDefaultProject() {
        workspaceService.initialize();

        assertTrue(workspaceService.list().isEmpty());
    }

    /** 验证项目使用不可猜测 ID 目录，名称不参与路径生成。 */
    @Test
    void createsManagedProjectWithOpaqueDirectory() throws Exception {
        workspaceService.initialize();

        Workspace created = workspaceService.create("Project");

        Path root = Path.of(created.rootPath());
        assertEquals(storageRoot.toRealPath(), root.getParent());
        assertEquals(created.id(), root.getFileName().toString());
        assertEquals("Project", created.name());
        assertEquals(WorkspaceType.MANAGED, created.type());
        assertTrue(Files.isDirectory(root));
        assertEquals(1, workspaceService.list().size());
    }

    /** 验证旧默认目录会整体迁移到项目 UUID 目录并保持会话绑定。 */
    @Test
    void migratesLegacyDefaultDirectoryWithoutChangingBindings() throws Exception {
        Path legacyRoot = Files.createDirectory(storageRoot.resolve("default"));
        Files.writeString(legacyRoot.resolve("README.md"), "legacy content");
        Workspace legacy = workspaceRepository.create(
                "project-1",
                "默认工作空间",
                legacyRoot.toString(),
                Instant.now()
        );
        conversationRepository.create("conversation-1", "Legacy", legacy.id(), Instant.now());

        workspaceService.initialize();

        Workspace migrated = workspaceService.get("project-1");
        Path migratedRoot = storageRoot.resolve("project-1");
        assertEquals(migratedRoot.toRealPath().toString(), migrated.rootPath());
        assertEquals("已有项目", migrated.name());
        assertEquals("project-1", conversationRepository.findById("conversation-1").orElseThrow().workspaceId());
        assertEquals("legacy content", Files.readString(migratedRoot.resolve("README.md")));
        assertFalse(Files.exists(legacyRoot));
    }

    /** 验证仍被对话引用的非托管目录会阻止启动，而不是静默改绑。 */
    @Test
    void rejectsReferencedProjectOutsideManagedStorage() {
        Workspace external = workspaceRepository.create(
                "external-project",
                "External",
                temporaryRoot.resolve("external").toString(),
                Instant.now()
        );
        conversationRepository.create("conversation-1", "External", external.id(), Instant.now());

        assertThrows(IllegalStateException.class, workspaceService::initialize);
    }

    /** 验证本地项目引用真实目录，解除注册时不会删除用户文件。 */
    @Test
    void registersAndSafelyRemovesLocalProject() throws Exception {
        workspaceService.initialize();
        Path localRoot = Files.createDirectory(temporaryRoot.resolve("local-project"));
        Path source = Files.writeString(localRoot.resolve("Main.java"), "class Main {}");

        Workspace workspace = workspaceService.registerLocal("Local", localRoot.toString());

        assertEquals(WorkspaceType.LOCAL, workspace.type());
        assertEquals(localRoot.toRealPath(), workspaceService.rootPath(workspace));
        workspaceService.delete(workspace.id());
        assertTrue(Files.exists(source));
        assertTrue(workspaceService.list().isEmpty());
    }
}
