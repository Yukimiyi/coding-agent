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
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在工作区内创建或原子覆盖 UTF-8 文本文件。
 */
@Component
public class WriteFileTool implements AgentTool {

    /** 向模型公开的文件写入参数协议。 */
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

    /** 解析安全的新文件或已有文件路径。 */
    private final WorkspacePathResolver pathResolver;
    /** 提供单次写入字节上限。 */
    private final WorkspaceProperties properties;
    /** 将写入结果序列化为工具 Observation。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建文件写入工具。
     *
     * @param pathResolver 工作空间路径解析器
     * @param properties 文件写入上限配置
     * @param objectMapper 结果 JSON 序列化器
     */
    public WriteFileTool(
            WorkspacePathResolver pathResolver,
            WorkspaceProperties properties,
            ObjectMapper objectMapper
    ) {
        this.pathResolver = pathResolver;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** @return {@code write_file} 工具协议定义 */
    @Override
    public DeepSeekToolDefinition definition() {
        return DEFINITION;
    }

    /**
     * 写入完整文件内容，并执行大小、覆盖权限和路径边界校验。
     *
     * @param arguments 包含路径、完整内容和可选覆盖标记的参数对象
     * @return 包含路径、字节数、创建及覆盖状态的 JSON 字符串
     * @throws ToolExecutionException 内容超限、路径无效、覆盖未授权或写入失败时抛出
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
                AtomicTextFileWriter.replace(path, content);
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

}
