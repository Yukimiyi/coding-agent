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

@Component
public class ListFilesTool implements AgentTool {

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

    private final WorkspacePathResolver pathResolver;
    private final WorkspaceProperties properties;
    private final ObjectMapper objectMapper;

    public ListFilesTool(
            WorkspacePathResolver pathResolver,
            WorkspaceProperties properties,
            ObjectMapper objectMapper
    ) {
        this.pathResolver = pathResolver;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public DeepSeekToolDefinition definition() {
        return DEFINITION;
    }

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

    public record Entry(String path, String type, Long size, Instant modifiedAt) {
    }
}
