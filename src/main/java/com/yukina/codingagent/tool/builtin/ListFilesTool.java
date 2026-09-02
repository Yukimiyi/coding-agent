package com.yukina.codingagent.tool.builtin;

import com.yukina.codingagent.deepseek.DeepSeekToolDefinition;
import com.yukina.codingagent.tool.AgentTool;
import com.yukina.codingagent.tool.ToolExecutionException;
import com.yukina.codingagent.tool.workspace.ToolArguments;
import com.yukina.codingagent.tool.workspace.ToolJson;
import com.yukina.codingagent.tool.workspace.WorkspaceFilePolicy;
import com.yukina.codingagent.tool.workspace.WorkspacePathResolver;
import com.yukina.codingagent.tool.workspace.WorkspaceProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在工作区中列出文件和目录，不跟随符号链接。
 */
@Component
public class ListFilesTool implements AgentTool {

    /** 向模型公开的目录遍历参数协议。 */
    private static final DeepSeekToolDefinition DEFINITION = DeepSeekToolDefinition.function(
            "list_files",
            "List files and directories in the workspace without following symbolic links.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "path", Map.of(
                                    "type", "string",
                                    "description", "Relative directory path",
                                    "default", "."
                            ),
                            "recursive", Map.of(
                                    "type", "boolean",
                                    "description", "Whether to recursively list descendants",
                                    "default", false
                            ),
                            "max_depth", Map.of(
                                    "type", "integer",
                                    "description", "Maximum recursive depth",
                                    "minimum", 1,
                                    "default", 3
                            ),
                            "max_entries", Map.of(
                                    "type", "integer",
                                    "description", "Maximum number of returned entries",
                                    "minimum", 1,
                                    "default", 100
                            )
                    ),
                    "additionalProperties", false
            )
    );

    /** 将起始路径和返回路径限制在当前工作空间。 */
    private final WorkspacePathResolver pathResolver;
    /** 提供目录深度及最大条目数量限制。 */
    private final WorkspaceProperties properties;
    /** 将列表条目序列化为工具 Observation。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建目录列表工具。
     *
     * @param pathResolver 工作空间路径解析器
     * @param properties 遍历深度和条目数量上限配置
     * @param objectMapper 结果 JSON 序列化器
     */
    public ListFilesTool(
            WorkspacePathResolver pathResolver,
            WorkspaceProperties properties,
            ObjectMapper objectMapper
    ) {
        this.pathResolver = pathResolver;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** @return {@code list_files} 工具协议定义 */
    @Override
    public DeepSeekToolDefinition definition() {
        return DEFINITION;
    }

    /**
     * 按深度和数量上限遍历目录，并跳过构建产物目录。
     *
     * @param arguments 包含起始路径、递归标记、深度及条目上限的参数对象
     * @return 包含目录条目和截断状态的 JSON 字符串
     * @throws ToolExecutionException 路径不是目录、参数越界或遍历失败时抛出
     */
    @Override
    public String execute(JsonNode arguments) {
        Path start = pathResolver.resolveExisting(ToolArguments.optionalText(arguments, "path", "."));
        if (!Files.isDirectory(start)) {
            throw new ToolExecutionException("NOT_A_DIRECTORY", "Path is not a directory: " + pathResolver.display(start));
        }

        boolean recursive = ToolArguments.optionalBoolean(arguments, "recursive", false);
        int depth = recursive
                ? ToolArguments.optionalInt(arguments, "max_depth", 3, 1, properties.maxDepth())
                : 1;
        int limit = ToolArguments.optionalInt(
                arguments,
                "max_entries",
                Math.min(100, properties.maxListEntries()),
                1,
                properties.maxListEntries()
        );
        List<Entry> entries = new ArrayList<>();

        try {
            Files.walkFileTree(start, java.util.Set.of(), depth, new SimpleFileVisitor<>() {
                /**
                 * 在进入目录前应用排除策略和数量限制。
                 *
                 * @param directory 即将进入的目录
                 * @param attributes 目录基础属性
                 * @return 继续、跳过子树或终止遍历的控制结果
                 */
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (!directory.equals(start)) {
                        if (WorkspaceFilePolicy.isExcludedDirectory(directory)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (addEntry(entries, directory, attributes, limit)) {
                            return FileVisitResult.TERMINATE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                /**
                 * 收集普通文件、符号链接和其他文件类型。
                 *
                 * @param file 当前文件
                 * @param attributes 文件基础属性
                 * @return 继续或终止遍历的控制结果
                 */
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    return addEntry(entries, file, attributes, limit)
                            ? FileVisitResult.TERMINATE
                            : FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new ToolExecutionException("DIRECTORY_LIST_FAILED", "Failed to list directory: " + pathResolver.display(start));
        }

        boolean truncated = entries.size() > limit;
        List<Entry> visibleEntries = entries.stream()
                .sorted(Comparator.comparing(Entry::path))
                .limit(limit)
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", pathResolver.display(start));
        result.put("entries", visibleEntries);
        result.put("truncated", truncated);
        return ToolJson.serialize(objectMapper, result);
    }

    /**
     * 将一个文件系统对象转换为列表项，并报告是否已超过返回上限。
     *
     * @param entries 接收条目的可变列表
     * @param path 当前文件系统路径
     * @param attributes 当前路径基础属性
     * @param limit 最大可见条目数
     * @return 加入当前条目后数量超过上限时返回 {@code true}
     */
    private boolean addEntry(List<Entry> entries, Path path, BasicFileAttributes attributes, int limit) {
        String type;
        Long size = null;
        if (attributes.isSymbolicLink() || Files.isSymbolicLink(path)) {
            type = "symlink";
        } else if (attributes.isDirectory()) {
            type = "directory";
        } else if (attributes.isRegularFile()) {
            type = "file";
            try {
                size = Files.size(path);
            } catch (IOException ignored) {
                size = null;
            }
        } else {
            type = "other";
        }
        Instant modifiedAt;
        try {
            modifiedAt = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant();
        } catch (IOException ignored) {
            modifiedAt = null;
        }
        entries.add(new Entry(pathResolver.display(path), type, size, modifiedAt));
        return entries.size() > limit;
    }

    /**
     * 目录列表中的单个文件系统对象。
     *
     * @param path 工作空间相对路径
     * @param type directory、file、symlink 或 other
     * @param size 普通文件字节数，其他类型为 {@code null}
     * @param modifiedAt 最后修改时间
     */
    public record Entry(String path, String type, Long size, Instant modifiedAt) {
    }
}
