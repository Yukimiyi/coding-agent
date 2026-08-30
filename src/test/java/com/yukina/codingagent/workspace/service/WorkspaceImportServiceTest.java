package com.yukina.codingagent.workspace.service;

import com.yukina.codingagent.workspace.WorkspaceImportProperties;
import com.yukina.codingagent.workspace.WorkspaceRegistryProperties;
import com.yukina.codingagent.workspace.exception.WorkspaceConflictException;
import com.yukina.codingagent.workspace.model.Workspace;
import com.yukina.codingagent.workspace.model.WorkspaceImportResult;
import com.yukina.codingagent.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证上传文件和粘贴源码的路径边界、冲突处理及目录结构。 */
class WorkspaceImportServiceTest {

    @TempDir
    Path temporaryDirectory;

    private EmbeddedDatabase database;
    private WorkspaceService workspaceService;
    private WorkspaceImportService importService;
    private Workspace workspace;

    /** 创建真实托管目录、仓储和导入服务。 */
    @BeforeEach
    void setUp() throws Exception {
        database = new EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .addScript("schema.sql")
                .build();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(database);
        WorkspaceRepository workspaceRepository = new WorkspaceRepository(jdbcTemplate);
        Path storageRoot = Files.createDirectory(temporaryDirectory.resolve("storage"));
        workspaceService = new WorkspaceService(
                workspaceRepository,
                new WorkspaceRegistryProperties(10, storageRoot)
        );
        workspaceService.initialize();
        workspace = workspaceService.create("Upload test");
        importService = new WorkspaceImportService(
                workspaceService,
                new WorkspaceImportProperties(5, 64, 128)
        );
    }

    /** 关闭当前用例数据库。 */
    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    /** 验证文件夹上传会保留浏览器传入的相对目录结构。 */
    @Test
    void importsFilesWithRelativeDirectories() throws Exception {
        MockMultipartFile javaFile = file("Main.java", "class Main {}");
        MockMultipartFile configFile = file("app.yml", "name: demo");

        WorkspaceImportResult result = importService.importFiles(
                workspace.id(),
                List.of(javaFile, configFile),
                List.of("demo/src/Main.java", "demo/config/app.yml")
        );

        Path root = Path.of(workspace.rootPath());
        assertEquals(2, result.importedFiles());
        assertEquals("class Main {}", Files.readString(root.resolve("demo/src/Main.java")));
        assertEquals("name: demo", Files.readString(root.resolve("demo/config/app.yml")));
    }

    /** 验证路径穿越会被拒绝，且不会在空间外产生文件。 */
    @Test
    void rejectsPathTraversal() {
        assertThrows(
                IllegalArgumentException.class,
                () -> importService.importFiles(
                        workspace.id(),
                        List.of(file("escape.txt", "blocked")),
                        List.of("../escape.txt")
                )
        );

        assertFalse(Files.exists(Path.of(workspace.rootPath()).getParent().resolve("escape.txt")));
    }

    /** 验证导入不会覆盖工作空间中的已有文件。 */
    @Test
    void rejectsExistingFile() {
        importService.importCode(workspace.id(), "src/Main.java", "first");

        assertThrows(
                WorkspaceConflictException.class,
                () -> importService.importCode(workspace.id(), "src/Main.java", "second")
        );
    }

    /** 验证粘贴源码通过 UTF-8 写入指定相对路径。 */
    @Test
    void importsPastedCode() throws Exception {
        WorkspaceImportResult result = importService.importCode(
                workspace.id(),
                "src/Hello.java",
                "public class Hello {}"
        );

        assertEquals(List.of("src/Hello.java"), result.paths());
        assertEquals(
                "public class Hello {}",
                Files.readString(Path.of(workspace.rootPath()).resolve("src/Hello.java"))
        );
    }

    /** 创建 UTF-8 测试上传文件。 */
    private static MockMultipartFile file(String name, String content) {
        return new MockMultipartFile(
                "files",
                name,
                "text/plain",
                content.getBytes(StandardCharsets.UTF_8)
        );
    }
}
