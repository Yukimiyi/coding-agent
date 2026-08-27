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

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Component
public class SearchTextTool implements AgentTool {

    private static final int MAX_LINE_LENGTH = 500;
    private static final DeepSeekToolDefinition DEFINITION = DeepSeekToolDefinition.function(
            "search_text",
            "Search UTF-8 workspace files for text or a regular expression without following symbolic links.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "query", Map.of("type", "string", "description", "Text or regular expression to search for"),
                            "path", Map.of("type", "string", "description", "Relative file or directory path", "default", "."),
                            "file_pattern", Map.of("type", "string", "description", "File name glob such as *.java", "default", "*"),
                            "case_sensitive", Map.of("type", "boolean", "default", false),
                            "regex", Map.of("type", "boolean", "default", false),
                            "max_results", Map.of("type", "integer", "minimum", 1, "default", 50),
                            "max_depth", Map.of("type", "integer", "minimum", 1, "default", 10)
                    ),
                    "required", List.of("query"),
                    "additionalProperties", false
            )
    );

    private final WorkspacePathResolver pathResolver;
    private final WorkspaceProperties properties;
    private final ObjectMapper objectMapper;

    public SearchTextTool(
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
        String query = ToolArguments.requiredText(arguments, "query");
        Path start = pathResolver.resolveExisting(ToolArguments.optionalText(arguments, "path", "."));
        int limit = ToolArguments.optionalInt(
                arguments,
                "max_results",
                Math.min(50, properties.maxSearchResults()),
                1,
                properties.maxSearchResults()
        );
        int depth = ToolArguments.optionalInt(arguments, "max_depth", properties.maxDepth(), 1, properties.maxDepth());
        Pattern searchPattern = compilePattern(
                query,
                ToolArguments.optionalBoolean(arguments, "regex", false),
                ToolArguments.optionalBoolean(arguments, "case_sensitive", false)
        );
        PathMatcher fileMatcher = compileFileMatcher(ToolArguments.optionalText(arguments, "file_pattern", "*"));
        List<Match> matches = new ArrayList<>();
        AtomicInteger skippedFiles = new AtomicInteger();

        try {
            if (Files.isRegularFile(start, LinkOption.NOFOLLOW_LINKS)) {
                searchFile(start, fileMatcher, searchPattern, matches, limit, skippedFiles);
            } else if (Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) {
                searchDirectory(start, depth, fileMatcher, searchPattern, matches, limit, skippedFiles);
            } else {
                throw new ToolExecutionException("UNSUPPORTED_PATH", "Search path must be a regular file or directory");
            }
        } catch (IOException exception) {
            throw new ToolExecutionException("TEXT_SEARCH_FAILED", "Failed to search path: " + pathResolver.display(start));
        }

        boolean truncated = matches.size() > limit;
        List<Match> visibleMatches = matches.stream()
                .sorted(Comparator.comparing(Match::path).thenComparingInt(Match::line))
                .limit(limit)
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("path", pathResolver.display(start));
        result.put("matches", visibleMatches);
        result.put("truncated", truncated);
        result.put("skippedFiles", skippedFiles.get());
        return ToolJson.serialize(objectMapper, result);
    }

    private void searchDirectory(
            Path start,
            int depth,
            PathMatcher fileMatcher,
            Pattern searchPattern,
            List<Match> matches,
            int limit,
            AtomicInteger skippedFiles
    ) throws IOException {
        Files.walkFileTree(start, java.util.Set.of(), depth, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (!directory.equals(start) && WorkspaceFilePolicy.isExcludedDirectory(directory)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isRegularFile()) {
                    searchFile(file, fileMatcher, searchPattern, matches, limit, skippedFiles);
                }
                return matches.size() > limit ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
        });
    }

    private void searchFile(
            Path file,
            PathMatcher fileMatcher,
            Pattern searchPattern,
            List<Match> matches,
            int limit,
            AtomicInteger skippedFiles
    ) throws IOException {
        if (!fileMatcher.matches(file.getFileName()) || Files.size(file) > properties.maxSearchFileBytes()) {
            skippedFiles.incrementAndGet();
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                Matcher matcher = searchPattern.matcher(line);
                if (matcher.find()) {
                    matches.add(new Match(
                            pathResolver.display(file),
                            lineNumber,
                            matcher.start() + 1,
                            abbreviate(line)
                    ));
                    if (matches.size() > limit) {
                        return;
                    }
                }
            }
        } catch (MalformedInputException exception) {
            skippedFiles.incrementAndGet();
        }
    }

    private static Pattern compilePattern(String query, boolean regex, boolean caseSensitive) {
        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        try {
            return Pattern.compile(regex ? query : Pattern.quote(query), flags);
        } catch (PatternSyntaxException exception) {
            throw new ToolExecutionException("INVALID_REGEX", "query is not a valid regular expression");
        }
    }

    private static PathMatcher compileFileMatcher(String glob) {
        if (glob.isBlank()) {
            throw new ToolExecutionException("INVALID_ARGUMENTS", "file_pattern must not be blank");
        }
        try {
            return FileSystems.getDefault().getPathMatcher("glob:" + glob);
        } catch (PatternSyntaxException exception) {
            throw new ToolExecutionException("INVALID_FILE_PATTERN", "file_pattern is not a valid glob");
        }
    }

    private static String abbreviate(String line) {
        return line.length() <= MAX_LINE_LENGTH ? line : line.substring(0, MAX_LINE_LENGTH) + "...";
    }

    public record Match(String path, int line, int column, String text) {
    }
}
