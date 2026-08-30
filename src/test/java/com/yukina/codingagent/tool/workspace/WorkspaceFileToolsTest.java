package com.yukina.codingagent.tool.workspace;

import com.yukina.codingagent.tool.ToolExecutionException;
import com.yukina.codingagent.tool.builtin.ListFilesTool;
import com.yukina.codingagent.tool.builtin.ReadFileTool;
import com.yukina.codingagent.tool.builtin.SearchTextTool;
import com.yukina.codingagent.tool.builtin.WriteFileTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证工作区文件工具的功能和安全边界。 */
class WorkspaceFileToolsTest {

    @TempDir
    Path workspace;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WorkspacePathResolver pathResolver;
    private WorkspaceExecutionContext executionContext;
    private WorkspaceProperties properties;

    /** 为每个用例创建隔离的临时工作区和工具实例。 */
    @BeforeEach
    void setUp() {
        properties = new WorkspaceProperties(
                workspace,
                1024,
                1024,
                100,
                50,
                1024,
                5
        );
        executionContext = new WorkspaceExecutionContext(properties);
        pathResolver = new WorkspacePathResolver(executionContext);
    }

    /** 验证 UTF-8 文件可以写入并完整读回。 */
    @Test
    void writesAndReadsUtf8File() throws Exception {
        WriteFileTool writeFile = new WriteFileTool(pathResolver, properties, objectMapper);
        ReadFileTool readFile = new ReadFileTool(pathResolver, properties, objectMapper);

        JsonNode writeResult = objectMapper.readTree(writeFile.execute(objectMapper.valueToTree(Map.of(
                "path", "src/Hello.java",
                "content", "class Hello {}\n"
        ))));
        JsonNode readResult = objectMapper.readTree(readFile.execute(objectMapper.readTree("""
                {"path":"src/Hello.java"}
                """)));

        assertTrue(writeResult.path("created").asBoolean());
        assertFalse(writeResult.path("overwritten").asBoolean());
        assertEquals("src/Hello.java", readResult.path("path").asText());
        assertEquals("class Hello {}\n", readResult.path("content").asText());
    }

    /** 验证覆盖已有文件必须显式授权。 */
    @Test
    void requiresExplicitPermissionToOverwrite() throws Exception {
        WriteFileTool writeFile = new WriteFileTool(pathResolver, properties, objectMapper);
        writeFile.execute(objectMapper.readTree("""
                {"path":"notes.txt","content":"first"}
                """));

        ToolExecutionException exception = assertThrows(
                ToolExecutionException.class,
                () -> writeFile.execute(objectMapper.readTree("""
                        {"path":"notes.txt","content":"second"}
                        """))
        );
        JsonNode result = objectMapper.readTree(writeFile.execute(objectMapper.readTree("""
                {"path":"notes.txt","content":"second","overwrite":true}
                """)));

        assertEquals("FILE_ALREADY_EXISTS", exception.code());
        assertTrue(result.path("overwritten").asBoolean());
        assertEquals("second", Files.readString(workspace.resolve("notes.txt")));
    }

    /** 验证绝对路径和父目录穿越会被拒绝。 */
    @Test
    void rejectsPathsOutsideWorkspace() {
        ToolExecutionException traversal = assertThrows(
                ToolExecutionException.class,
                () -> pathResolver.resolveForWrite("../outside.txt")
        );
        ToolExecutionException absolute = assertThrows(
                ToolExecutionException.class,
                () -> pathResolver.resolveForWrite(workspace.resolve("absolute.txt").toString())
        );

        assertEquals("PATH_OUTSIDE_WORKSPACE", traversal.code());
        assertEquals("ABSOLUTE_PATH_FORBIDDEN", absolute.code());
    }

    /** 验证文件读写字节上限生效。 */
    @Test
    void enforcesReadAndWriteSizeLimits() throws Exception {
        WorkspaceProperties smallLimits = new WorkspaceProperties(workspace, 4, 4, 100, 50, 1024, 5);
        ReadFileTool readFile = new ReadFileTool(pathResolver, smallLimits, objectMapper);
        WriteFileTool writeFile = new WriteFileTool(pathResolver, smallLimits, objectMapper);
        Files.writeString(workspace.resolve("large.txt"), "12345");

        ToolExecutionException readError = assertThrows(
                ToolExecutionException.class,
                () -> readFile.execute(objectMapper.readTree("{\"path\":\"large.txt\"}"))
        );
        ToolExecutionException writeError = assertThrows(
                ToolExecutionException.class,
                () -> writeFile.execute(objectMapper.readTree("{\"path\":\"new.txt\",\"content\":\"12345\"}"))
        );

        assertEquals("FILE_TOO_LARGE", readError.code());
        assertEquals("CONTENT_TOO_LARGE", writeError.code());
    }

    /** 验证递归列表会跳过构建产物目录。 */
    @Test
    void listsRecursivelyAndSkipsBuildDirectories() throws Exception {
        Files.createDirectories(workspace.resolve("src/main"));
        Files.createDirectories(workspace.resolve("target/classes"));
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        Files.writeString(workspace.resolve("src/main/App.java"), "class App {}");
        Files.writeString(workspace.resolve("target/classes/App.class"), "binary");
        ListFilesTool listFiles = new ListFilesTool(pathResolver, properties, objectMapper);

        JsonNode result = objectMapper.readTree(listFiles.execute(objectMapper.readTree("""
                {"path":".","recursive":true,"max_depth":5}
                """)));
        String entries = result.path("entries").toString();

        assertTrue(entries.contains("pom.xml"));
        assertTrue(entries.contains("src/main/App.java"));
        assertFalse(entries.contains("target"));
        assertFalse(result.path("truncated").asBoolean());
    }

    /** 验证文本搜索支持 Glob、大小写和正则选项。 */
    @Test
    void searchesTextWithGlobCaseAndRegexOptions() throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/One.java"), "Hello world\nsecond line\n");
        Files.writeString(workspace.resolve("src/Two.java"), "HELLO again\n");
        Files.writeString(workspace.resolve("src/notes.txt"), "Hello but excluded by glob\n");
        SearchTextTool searchText = new SearchTextTool(pathResolver, properties, objectMapper);

        JsonNode plain = objectMapper.readTree(searchText.execute(objectMapper.readTree("""
                {"query":"hello","path":"src","file_pattern":"*.java"}
                """)));
        JsonNode regex = objectMapper.readTree(searchText.execute(objectMapper.valueToTree(Map.of(
                "query", "second\\s+line",
                "path", "src",
                "file_pattern", "*.java",
                "regex", true
        ))));

        assertEquals(2, plain.path("matches").size());
        assertEquals(1, regex.path("matches").size());
        assertEquals(2, regex.path("matches").get(0).path("line").asInt());
    }

    /** 验证搜索遇到非 UTF-8 文件时跳过而不中断。 */
    @Test
    void skipsInvalidUtf8DuringSearch() throws Exception {
        Files.write(workspace.resolve("binary.dat"), new byte[]{(byte) 0xC3, (byte) 0x28});
        SearchTextTool searchText = new SearchTextTool(pathResolver, properties, objectMapper);

        JsonNode result = objectMapper.readTree(searchText.execute(objectMapper.readTree("""
                {"query":"anything","path":"."}
                """)));

        assertEquals(0, result.path("matches").size());
        assertEquals(1, result.path("skippedFiles").asInt());
    }

    /** 验证同一个工具实例会按当前运行上下文隔离两个目录。 */
    @Test
    void isolatesFilesByExecutionWorkspace() throws Exception {
        Path first = Files.createDirectory(workspace.resolve("first"));
        Path second = Files.createDirectory(workspace.resolve("second"));
        Files.writeString(first.resolve("marker.txt"), "FIRST");
        Files.writeString(second.resolve("marker.txt"), "SECOND");
        ReadFileTool readFile = new ReadFileTool(pathResolver, properties, objectMapper);
        JsonNode arguments = objectMapper.readTree("{\"path\":\"marker.txt\"}");

        String firstContent = executionContext.withWorkspace(
                first,
                () -> readContent(readFile, arguments)
        );
        String secondContent = executionContext.withWorkspace(
                second,
                () -> readContent(readFile, arguments)
        );

        assertEquals("FIRST", firstContent);
        assertEquals("SECOND", secondContent);
        assertEquals(workspace.toRealPath(), executionContext.root());
    }

    /** 执行读取工具并提取文本内容。 */
    private String readContent(ReadFileTool readFile, JsonNode arguments) {
        try {
            return objectMapper.readTree(readFile.execute(arguments)).path("content").asText();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
