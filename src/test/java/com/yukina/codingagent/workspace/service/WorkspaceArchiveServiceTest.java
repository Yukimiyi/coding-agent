package com.yukina.codingagent.workspace.service;

import com.yukina.codingagent.workspace.exception.WorkspaceConflictException;
import com.yukina.codingagent.workspace.model.Workspace;
import com.yukina.codingagent.workspace.model.WorkspaceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证受管项目归档的路径、内容和类型边界。 */
class WorkspaceArchiveServiceTest {

    @TempDir
    Path root;

    /** 验证嵌套项目文件会以稳定的相对路径写入 ZIP。 */
    @Test
    void archivesManagedWorkspaceFiles() throws Exception {
        Files.writeString(root.resolve("README.md"), "hello");
        Path source = Files.createDirectories(root.resolve("src"));
        Files.writeString(source.resolve("Main.java"), "class Main {}");
        Workspace workspace = workspace(WorkspaceType.MANAGED, "Demo:Project");
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        when(workspaceService.get("workspace-1")).thenReturn(workspace);
        when(workspaceService.rootPath(workspace)).thenReturn(root.toRealPath());
        WorkspaceArchiveService archiveService = new WorkspaceArchiveService(workspaceService);

        WorkspaceArchiveService.WorkspaceArchive archive = archiveService.prepare("workspace-1");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        archiveService.write(archive, output);

        assertEquals("Demo_Project.zip", archiveService.fileName(archive));
        assertEquals(
                Map.of("README.md", "hello", "src/Main.java", "class Main {}"),
                unzip(output.toByteArray())
        );
    }

    /** 验证本地目录注册不会通过网页归档接口导出。 */
    @Test
    void rejectsLocalWorkspace() {
        Workspace workspace = workspace(WorkspaceType.LOCAL, "Local project");
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        when(workspaceService.get("workspace-1")).thenReturn(workspace);
        WorkspaceArchiveService archiveService = new WorkspaceArchiveService(workspaceService);

        WorkspaceConflictException exception = assertThrows(
                WorkspaceConflictException.class,
                () -> archiveService.prepare("workspace-1")
        );

        assertEquals("Only managed projects can be downloaded", exception.getMessage());
    }

    /** 创建指定类型的测试项目。 */
    private Workspace workspace(WorkspaceType type, String name) {
        return new Workspace("workspace-1", name, type, root.toString(), Instant.EPOCH, Instant.EPOCH);
    }

    /** 将 ZIP 字节读取为路径到文本内容的映射。 */
    private static Map<String, String> unzip(byte[] bytes) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
