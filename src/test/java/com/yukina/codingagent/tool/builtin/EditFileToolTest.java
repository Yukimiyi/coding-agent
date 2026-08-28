package com.yukina.codingagent.tool.builtin;

import com.yukina.codingagent.tool.ToolExecutionException;
import com.yukina.codingagent.tool.workspace.WorkspacePathResolver;
import com.yukina.codingagent.tool.workspace.WorkspaceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证精确文件修改工具的替换行为和失败原子性。 */
class EditFileToolTest {

    @TempDir
    Path workspace;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WorkspacePathResolver pathResolver;
    private WorkspaceProperties properties;
    private EditFileTool editFile;

    /** 为每个用例创建隔离的临时工作区和工具实例。 */
    @BeforeEach
    void setUp() {
        properties = propertiesWithLimits(1024, 1024);
        pathResolver = new WorkspacePathResolver(properties);
        editFile = new EditFileTool(pathResolver, properties, objectMapper);
    }

    /** 验证唯一旧文本会被精确替换并返回修改统计。 */
    @Test
    void replacesUniqueText() throws Exception {
        Files.writeString(workspace.resolve("Example.java"), "class Example {\n    int value = 1;\n}\n");

        JsonNode result = execute(Map.of(
                "path", "Example.java",
                "old_text", "int value = 1;",
                "new_text", "int value = 2;"
        ));

        assertEquals(1, result.path("replacements").asInt());
        assertEquals("Example.java", result.path("path").asText());
        assertEquals("class Example {\n    int value = 2;\n}\n", Files.readString(workspace.resolve("Example.java")));
    }

    /** 验证空新文本可以删除目标片段且保留其余内容。 */
    @Test
    void deletesExactText() throws Exception {
        Files.writeString(workspace.resolve("notes.txt"), "first\r\nremove me\r\nlast\r\n");

        execute(Map.of(
                "path", "notes.txt",
                "old_text", "remove me\r\n",
                "new_text", ""
        ));

        assertEquals("first\r\nlast\r\n", Files.readString(workspace.resolve("notes.txt")));
    }

    /** 验证默认只允许唯一匹配，存在歧义时不修改文件。 */
    @Test
    void rejectsAmbiguousMatchWithoutChangingFile() throws Exception {
        Path path = workspace.resolve("values.txt");
        Files.writeString(path, "old old");

        ToolExecutionException exception = assertThrows(
                ToolExecutionException.class,
                () -> execute(Map.of(
                        "path", "values.txt",
                        "old_text", "old",
                        "new_text", "new"
                ))
        );

        assertEquals("REPLACEMENT_COUNT_MISMATCH", exception.code());
        assertEquals("old old", Files.readString(path));
    }

    /** 验证显式声明匹配次数后可以一次修改多处。 */
    @Test
    void replacesDeclaredNumberOfOccurrences() throws Exception {
        Files.writeString(workspace.resolve("values.txt"), "old old");

        JsonNode result = execute(Map.of(
                "path", "values.txt",
                "old_text", "old",
                "new_text", "new",
                "expected_replacements", 2
        ));

        assertEquals(2, result.path("replacements").asInt());
        assertEquals("new new", Files.readString(workspace.resolve("values.txt")));
    }

    /** 验证找不到旧文本时返回稳定错误并保留原文件。 */
    @Test
    void rejectsMissingOldTextWithoutChangingFile() throws Exception {
        Path path = workspace.resolve("notes.txt");
        Files.writeString(path, "current");

        ToolExecutionException exception = assertThrows(
                ToolExecutionException.class,
                () -> execute(Map.of(
                        "path", "notes.txt",
                        "old_text", "stale",
                        "new_text", "updated"
                ))
        );

        assertEquals("OLD_TEXT_NOT_FOUND", exception.code());
        assertEquals("current", Files.readString(path));
    }

    /** 验证修改后的内容超过写入上限时不覆盖原文件。 */
    @Test
    void rejectsOversizedResultWithoutChangingFile() throws Exception {
        Path path = workspace.resolve("small.txt");
        Files.writeString(path, "before");
        WorkspaceProperties smallWriteLimit = propertiesWithLimits(1024, 8);
        EditFileTool limitedEditFile = new EditFileTool(
                new WorkspacePathResolver(smallWriteLimit),
                smallWriteLimit,
                objectMapper
        );

        ToolExecutionException exception = assertThrows(
                ToolExecutionException.class,
                () -> objectMapper.readTree(limitedEditFile.execute(objectMapper.valueToTree(Map.of(
                        "path", "small.txt",
                        "old_text", "before",
                        "new_text", "replacement"
                ))))
        );

        assertEquals("CONTENT_TOO_LARGE", exception.code());
        assertEquals("before", Files.readString(path));
    }

    /** 验证二进制内容不会经过文本替换流程。 */
    @Test
    void rejectsBinaryFile() throws Exception {
        Files.write(workspace.resolve("binary.dat"), new byte[]{'a', 0, 'b'});

        ToolExecutionException exception = assertThrows(
                ToolExecutionException.class,
                () -> execute(Map.of(
                        "path", "binary.dat",
                        "old_text", "a",
                        "new_text", "c"
                ))
        );

        assertEquals("BINARY_FILE", exception.code());
    }

    /** 使用 JSON 对象调用工具并解析结构化结果。 */
    private JsonNode execute(Map<String, Object> arguments) throws Exception {
        return objectMapper.readTree(editFile.execute(objectMapper.valueToTree(arguments)));
    }

    /** 创建使用指定读写字节上限的工作区配置。 */
    private WorkspaceProperties propertiesWithLimits(long maxReadBytes, long maxWriteBytes) {
        return new WorkspaceProperties(
                workspace,
                maxReadBytes,
                maxWriteBytes,
                100,
                50,
                1024,
                5
        );
    }
}
