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

    private static final int MAX_EXPECTED_REPLACEMENTS = 10_000;
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

    private final WorkspacePathResolver pathResolver;
    private final WorkspaceProperties properties;
    private final ObjectMapper objectMapper;

    /** 创建精确文件修改工具。 */
    public EditFileTool(
            WorkspacePathResolver pathResolver,
            WorkspaceProperties properties,
            ObjectMapper objectMapper
    ) {
        this.pathResolver = pathResolver;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** {@inheritDoc} */
    @Override
    public DeepSeekToolDefinition definition() {
        return DEFINITION;
    }

    /**
     * 校验文件和匹配次数后执行原子替换，任一校验失败时保持原文件不变。
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

    /** 统计互不重叠的精确文本出现次数。 */
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
