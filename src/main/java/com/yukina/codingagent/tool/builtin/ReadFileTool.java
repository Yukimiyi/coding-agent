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
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在大小限制内读取工作区 UTF-8 文本文件。
 */
@Component
public class ReadFileTool implements AgentTool {

    private static final DeepSeekToolDefinition DEFINITION = DeepSeekToolDefinition.function(
            "read_file",
            "Read a UTF-8 text file from the workspace. The path must be relative to the workspace root.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "path", Map.of(
                                    "type", "string",
                                    "description", "Relative path of the file to read"
                            )
                    ),
                    "required", List.of("path"),
                    "additionalProperties", false
            )
    );

    private final WorkspacePathResolver pathResolver;
    private final WorkspaceProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 创建文件读取工具。
     *
     * @param pathResolver 工作空间路径解析器
     * @param properties 文件读取上限配置
     * @param objectMapper 结果 JSON 序列化器
     */
    public ReadFileTool(
            WorkspacePathResolver pathResolver,
            WorkspaceProperties properties,
            ObjectMapper objectMapper
    ) {
        this.pathResolver = pathResolver;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** @return {@code read_file} 工具协议定义 */
    @Override
    public DeepSeekToolDefinition definition() {
        return DEFINITION;
    }

    /**
     * 读取文件并拒绝目录、超大文件、二进制内容和非 UTF-8 文本。
     *
     * @param arguments 包含工作空间相对 {@code path} 的参数对象
     * @return 包含路径、字节数和完整文本内容的 JSON 字符串
     * @throws ToolExecutionException 路径、文件类型、大小、编码或读取操作不合法时抛出
     */
    @Override
    public String execute(JsonNode arguments) {
        Path path = pathResolver.resolveExisting(ToolArguments.requiredText(arguments, "path"));
        if (!Files.isRegularFile(path)) {
            throw new ToolExecutionException("NOT_A_FILE", "Path is not a regular file: " + pathResolver.display(path));
        }

        try {
            long size = Files.size(path);
            if (size > properties.maxReadBytes()) {
                throw new ToolExecutionException(
                        "FILE_TOO_LARGE",
                        "File exceeds the read limit of " + properties.maxReadBytes() + " bytes"
                );
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content.indexOf('\0') >= 0) {
                throw new ToolExecutionException("BINARY_FILE", "File appears to contain binary data");
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", pathResolver.display(path));
            result.put("bytes", size);
            result.put("content", content);
            return ToolJson.serialize(objectMapper, result);
        } catch (MalformedInputException exception) {
            throw new ToolExecutionException("BINARY_FILE", "File is not valid UTF-8 text");
        } catch (IOException exception) {
            throw new ToolExecutionException("FILE_READ_FAILED", "Failed to read file: " + pathResolver.display(path));
        }
    }
}
