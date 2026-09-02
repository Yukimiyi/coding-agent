package com.yukina.codingagent.tool.builtin;

import com.yukina.codingagent.deepseek.DeepSeekToolDefinition;
import com.yukina.codingagent.tool.AgentTool;
import com.yukina.codingagent.tool.ToolExecutionException;
import com.yukina.codingagent.tool.workspace.AtomicTextFileWriter;
import com.yukina.codingagent.tool.workspace.ToolArguments;
import com.yukina.codingagent.tool.workspace.ToolJson;
import com.yukina.codingagent.tool.workspace.WorkspacePathResolver;
import com.yukina.codingagent.tool.workspace.WorkspaceProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通过精确旧文本匹配修改工作区内的 UTF-8 文本文件。
 */
@Component
public class EditFileTool implements AgentTool {

    /** 单次精确替换允许声明的最大出现次数。 */
    private static final int MAX_EXPECTED_REPLACEMENTS = 10_000;
    /** 精确文件修改工具的稳定协议定义。 */
    private static final DeepSeekToolDefinition DEFINITION = DeepSeekToolDefinition.function(
            "edit_file",
            "Replace exact text in an existing UTF-8 workspace file. The file is changed only when old_text "
                    + "occurs exactly expected_replacements times, which defaults to one.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "path", Map.of(
                                    "type", "string",
                                    "description", "Relative path of the existing file to edit"
                            ),
                            "old_text", Map.of(
                                    "type", "string",
                                    "description", "Exact non-empty text currently present in the file",
                                    "minLength", 1
                            ),
                            "new_text", Map.of(
                                    "type", "string",
                                    "description", "Replacement text; use an empty string to delete old_text"
                            ),
                            "expected_replacements", Map.of(
                                    "type", "integer",
                                    "description", "Required number of non-overlapping old_text occurrences",
                                    "minimum", 1,
                                    "maximum", MAX_EXPECTED_REPLACEMENTS,
                                    "default", 1
                            )
                    ),
                    "required", List.of("path", "old_text", "new_text"),
                    "additionalProperties", false
            )
    );

    /** 将相对路径约束在当前会话工作区内的解析器。 */
    private final WorkspacePathResolver pathResolver;
    /** 文件读取和写入字节数安全上限。 */
    private final WorkspaceProperties properties;
    /** 将修改结果序列化为工具 Observation JSON 的映射器。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建精确文件修改工具。
     *
     * @param pathResolver 工作空间路径解析器
     * @param properties 文件读写上限配置
     * @param objectMapper 结果 JSON 序列化器
     */
    public EditFileTool(
            WorkspacePathResolver pathResolver,
            WorkspaceProperties properties,
            ObjectMapper objectMapper
    ) {
        this.pathResolver = pathResolver;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** @return {@code edit_file} 工具协议定义 */
    @Override
    public DeepSeekToolDefinition definition() {
        return DEFINITION;
    }

    /**
     * 校验文件和匹配次数后执行原子替换，任一校验失败时保持原文件不变。
     *
     * @param arguments 包含路径、旧文本、新文本和可选期望替换次数的参数对象
     * @return 包含替换次数及修改前后字节数的 JSON 字符串
     * @throws ToolExecutionException 参数、路径、编码、匹配次数或文件大小不符合要求时抛出
     */
    @Override
    public String execute(JsonNode arguments) {
        String oldText = ToolArguments.requiredText(arguments, "old_text", true);
        String newText = ToolArguments.requiredText(arguments, "new_text", true);
        if (oldText.isEmpty()) {
            throw new ToolExecutionException("INVALID_ARGUMENTS", "old_text must not be empty");
        }
        if (oldText.equals(newText)) {
            throw new ToolExecutionException("NO_CHANGES", "old_text and new_text must be different");
        }
        int expectedReplacements = ToolArguments.optionalInt(
                arguments,
                "expected_replacements",
                1,
                1,
                MAX_EXPECTED_REPLACEMENTS
        );

        Path path = pathResolver.resolveExisting(ToolArguments.requiredText(arguments, "path"));
        if (Files.isSymbolicLink(path)) {
            throw new ToolExecutionException(
                    "SYMLINK_WRITE_FORBIDDEN",
                    "Editing through a symbolic link is not allowed"
            );
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new ToolExecutionException("NOT_A_FILE", "Path is not a regular file: " + pathResolver.display(path));
        }

        try {
            long beforeBytes = Files.size(path);
            if (beforeBytes > properties.maxReadBytes()) {
                throw new ToolExecutionException(
                        "FILE_TOO_LARGE",
                        "File exceeds the read limit of " + properties.maxReadBytes() + " bytes"
                );
            }

            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content.indexOf('\0') >= 0) {
                throw new ToolExecutionException("BINARY_FILE", "File appears to contain binary data");
            }

            int actualReplacements = countOccurrences(content, oldText);
            if (actualReplacements == 0) {
                throw new ToolExecutionException("OLD_TEXT_NOT_FOUND", "old_text was not found in the file");
            }
            if (actualReplacements != expectedReplacements) {
                throw new ToolExecutionException(
                        "REPLACEMENT_COUNT_MISMATCH",
                        "Expected " + expectedReplacements + " replacement(s), but found " + actualReplacements
                );
            }

            String updatedContent = content.replace(oldText, newText);
            byte[] updatedBytes = updatedContent.getBytes(StandardCharsets.UTF_8);
            if (updatedBytes.length > properties.maxWriteBytes()) {
                throw new ToolExecutionException(
                        "CONTENT_TOO_LARGE",
                        "Edited content exceeds the write limit of " + properties.maxWriteBytes() + " bytes"
                );
            }

            AtomicTextFileWriter.replace(path, updatedContent);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", pathResolver.display(path));
            result.put("replacements", actualReplacements);
            result.put("bytesBefore", beforeBytes);
            result.put("bytesAfter", updatedBytes.length);
            return ToolJson.serialize(objectMapper, result);
        } catch (MalformedInputException exception) {
            throw new ToolExecutionException("BINARY_FILE", "File is not valid UTF-8 text");
        } catch (IOException exception) {
            throw new ToolExecutionException("FILE_EDIT_FAILED", "Failed to edit file: " + pathResolver.display(path));
        }
    }

    /**
     * 统计互不重叠的精确文本出现次数。
     *
     * @param content 完整文件文本
     * @param target 非空目标文本
     * @return 目标文本互不重叠的出现次数
     */
    private static int countOccurrences(String content, String target) {
        int count = 0;
        int fromIndex = 0;
        while (true) {
            int matchIndex = content.indexOf(target, fromIndex);
            if (matchIndex < 0) {
                return count;
            }
            count++;
            fromIndex = matchIndex + target.length();
        }
    }
}
