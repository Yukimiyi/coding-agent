package com.yukina.codingagent.tool.builtin;

import com.yukina.codingagent.deepseek.DeepSeekToolDefinition;
import com.yukina.codingagent.tool.AgentTool;
import com.yukina.codingagent.tool.ToolExecutionException;
import com.yukina.codingagent.tool.workspace.ToolArguments;
import com.yukina.codingagent.tool.workspace.ToolJson;
import com.yukina.codingagent.tool.workspace.WorkspacePathResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 删除工作空间内的单个普通文件，不允许目录、符号链接或路径逃逸。
 */
@Component
public class DeleteFileTool implements AgentTool {

    private static final DeepSeekToolDefinition DEFINITION = DeepSeekToolDefinition.function(
            "delete_file",
            "Delete one regular file inside the workspace. Use this only for temporary files you created; "
                    + "never use operating-system deletion commands.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "path", Map.of(
                                    "type", "string",
                                    "description", "Workspace-relative path of the file to delete"
                            )
                    ),
                    "required", List.of("path"),
                    "additionalProperties", false
            )
    );

    private final WorkspacePathResolver pathResolver;
    private final ObjectMapper objectMapper;

    /**
     * 创建受控文件删除工具。
     *
     * @param pathResolver 工作空间路径解析器
     * @param objectMapper 结果 JSON 序列化器
     */
    public DeleteFileTool(WorkspacePathResolver pathResolver, ObjectMapper objectMapper) {
        this.pathResolver = pathResolver;
        this.objectMapper = objectMapper;
    }

    /** @return {@code delete_file} 工具协议定义 */
    @Override
    public DeepSeekToolDefinition definition() {
        return DEFINITION;
    }

    /**
     * 删除经过真实路径校验的普通文件并返回删除记录。
     *
     * @param arguments 包含工作空间相对 {@code path} 的参数对象
     * @return 包含路径、原字节数和删除状态的 JSON 字符串
     * @throws ToolExecutionException 路径无效、不是普通文件、为符号链接或删除失败时抛出
     */
    @Override
    public String execute(JsonNode arguments) {
        Path path = pathResolver.resolveExisting(ToolArguments.requiredText(arguments, "path"));
        if (Files.isSymbolicLink(path)) {
            throw new ToolExecutionException("SYMLINK_DELETE_FORBIDDEN", "Deleting a symbolic link is not allowed");
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new ToolExecutionException("NOT_A_FILE", "Path is not a regular file: " + pathResolver.display(path));
        }
        String displayPath = pathResolver.display(path);
        try {
            long bytes = Files.size(path);
            Files.delete(path);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", displayPath);
            result.put("bytes", bytes);
            result.put("deleted", true);
            return ToolJson.serialize(objectMapper, result);
        } catch (IOException exception) {
            throw new ToolExecutionException("FILE_DELETE_FAILED", "Failed to delete file: " + displayPath);
        }
    }
}
