package com.yukina.codingagent.tool.builtin;

import com.yukina.codingagent.deepseek.DeepSeekToolDefinition;
import com.yukina.codingagent.tool.AgentTool;
import com.yukina.codingagent.tool.ToolExecutionException;
import com.yukina.codingagent.tool.workspace.ToolArguments;
import com.yukina.codingagent.tool.workspace.ToolJson;
import com.yukina.codingagent.tool.workspace.WorkspacePathResolver;
import com.yukina.codingagent.tool.workspace.WorkspaceProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在工作区内创建或原子覆盖 UTF-8 文本文件。
 */
@Component
public class WriteFileTool implements AgentTool {

    private static final DeepSeekToolDefinition DEFINITION = DeepSeekToolDefinition.function(
            "write_file",
            "Write complete UTF-8 text content to a workspace file. Set overwrite=true to replace an existing file.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "path", Map.of(
                                    "type", "string",
                                    "description", "Relative path of the file to write"
                            ),
                            "content", Map.of(
                                    "type", "string",
                                    "description", "Complete UTF-8 file content"
                            ),
                            "overwrite", Map.of(
                                    "type", "boolean",
                                    "description", "Whether an existing file may be replaced",
                                    "default", false
                            )
                    ),
                    "required", List.of("path", "content"),
                    "additionalProperties", false
            )
    );

    private final WorkspacePathResolver pathResolver;
    private final WorkspaceProperties properties;
    private final ObjectMapper objectMapper;

    /** 创建文件写入工具。 */
    public WriteFileTool(
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
     * 写入完整文件内容，并执行大小、覆盖权限和路径边界校验。
     */
    @Override
    public String execute(JsonNode arguments) {
        String content = ToolArguments.requiredText(arguments, "content", true);
        boolean overwrite = ToolArguments.optionalBoolean(arguments, "overwrite", false);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > properties.maxWriteBytes()) {
            throw new ToolExecutionException(
                    "CONTENT_TOO_LARGE",
                    "Content exceeds the write limit of " + properties.maxWriteBytes() + " bytes"
            );
        }

        Path path = pathResolver.resolveForWrite(ToolArguments.requiredText(arguments, "path"));
        boolean existed = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        if (existed && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new ToolExecutionException("NOT_A_FILE", "Path is a directory: " + pathResolver.display(path));
        }
        if (existed && !overwrite) {
            throw new ToolExecutionException(
                    "FILE_ALREADY_EXISTS",
                    "File already exists; set overwrite=true to replace it"
            );
        }

        Path parent = path.getParent();
        try {
            Files.createDirectories(parent);
            pathResolver.ensureDirectoryInsideWorkspace(parent);
            if (overwrite) {
                atomicWrite(path, content);
            } else {
                Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", pathResolver.display(path));
            result.put("bytes", bytes.length);
            result.put("created", !existed);
            result.put("overwritten", existed);
            return ToolJson.serialize(objectMapper, result);
        } catch (FileAlreadyExistsException exception) {
            throw new ToolExecutionException("FILE_ALREADY_EXISTS", "File already exists");
        } catch (IOException exception) {
            throw new ToolExecutionException("FILE_WRITE_FAILED", "Failed to write file: " + pathResolver.display(path));
        }
    }

    /**
     * 先写临时文件再替换目标，平台不支持原子移动时使用普通替换。
     */
    private static void atomicWrite(Path path, String content) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), ".coding-agent-", ".tmp");
        try {
            Files.writeString(
                    temporary,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
