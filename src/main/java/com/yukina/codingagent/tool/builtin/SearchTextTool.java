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

/**
 * 在工作区 UTF-8 文件中执行文本或正则表达式搜索。
 */
@Component
public class SearchTextTool implements AgentTool {

    /** 单条搜索结果允许返回的最大文本长度。 */
    private static final int MAX_LINE_LENGTH = 500;
    /** 向模型公开的文本搜索参数协议。 */
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

    /** 将搜索根目录和结果路径限制在当前工作空间。 */
    private final WorkspacePathResolver pathResolver;
    /** 提供遍历深度、文件大小和结果数量限制。 */
    private final WorkspaceProperties properties;
    /** 将搜索结果序列化为工具 Observation。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建文本搜索工具。
     *
     * @param pathResolver 工作空间路径解析器
     * @param properties 搜索文件、深度和结果上限配置
     * @param objectMapper 结果 JSON 序列化器
     */
    public SearchTextTool(
            WorkspacePathResolver pathResolver,
            WorkspaceProperties properties,
            ObjectMapper objectMapper
    ) {
        this.pathResolver = pathResolver;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** @return {@code search_text} 工具协议定义 */
    @Override
    public DeepSeekToolDefinition definition() {
        return DEFINITION;
    }

    /**
     * 按文件模式、深度和结果数量限制搜索文本。
     *
     * @param arguments 包含查询文本、路径、Glob、大小写及正则选项的参数对象
     * @return 包含匹配位置、截断状态和跳过文件数量的 JSON 字符串
     * @throws ToolExecutionException 参数、路径、正则、Glob 或遍历操作不合法时抛出
     */
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

    /**
     * 遍历目录并在达到结果上限后提前终止。
     *
     * @param start 搜索起始目录
     * @param depth 最大遍历深度
     * @param fileMatcher 文件名 Glob 匹配器
     * @param searchPattern 文本或正则搜索模式
     * @param matches 接收匹配结果的可变列表
     * @param limit 最大可见结果数
     * @param skippedFiles 跳过文件计数器
     * @throws IOException 目录遍历或文件读取失败时抛出
     */
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
            /**
             * 在遍历前跳过排除目录。
             *
             * @param directory 即将进入的目录
             * @param attributes 目录基础属性
             * @return 继续或跳过子树的控制结果
             */
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (!directory.equals(start) && WorkspaceFilePolicy.isExcludedDirectory(directory)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            /**
             * 搜索符合条件的普通文件。
             *
             * @param file 当前文件
             * @param attributes 文件基础属性
             * @return 继续或终止遍历的控制结果
             * @throws IOException 文件读取失败时抛出
             */
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isRegularFile()) {
                    searchFile(file, fileMatcher, searchPattern, matches, limit, skippedFiles);
                }
                return matches.size() > limit ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 逐行搜索单个文件；超大或非 UTF-8 文件计入跳过数量。
     *
     * @param file 待搜索文件
     * @param fileMatcher 文件名 Glob 匹配器
     * @param searchPattern 文本或正则搜索模式
     * @param matches 接收匹配结果的可变列表
     * @param limit 最大可见结果数
     * @param skippedFiles 跳过文件计数器
     * @throws IOException 文件属性或内容读取失败时抛出
     */
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

    /**
     * 将普通文本或正则查询编译为搜索模式。
     *
     * @param query 查询文本
     * @param regex 是否将查询解释为正则表达式
     * @param caseSensitive 是否区分大小写
     * @return 编译后的搜索模式
     * @throws ToolExecutionException 正则表达式无效时抛出
     */
    private static Pattern compilePattern(String query, boolean regex, boolean caseSensitive) {
        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        try {
            return Pattern.compile(regex ? query : Pattern.quote(query), flags);
        } catch (PatternSyntaxException exception) {
            throw new ToolExecutionException("INVALID_REGEX", "query is not a valid regular expression");
        }
    }

    /**
     * 编译文件名 Glob，并转换无效模式异常。
     *
     * @param glob 文件名 Glob 表达式
     * @return 当前文件系统的路径匹配器
     * @throws ToolExecutionException 模式为空或语法无效时抛出
     */
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

    /**
     * 截断过长匹配行，限制工具返回体大小。
     *
     * @param line 原始匹配行
     * @return 不超过固定长度的行摘要
     */
    private static String abbreviate(String line) {
        return line.length() <= MAX_LINE_LENGTH ? line : line.substring(0, MAX_LINE_LENGTH) + "...";
    }

    /**
     * 单个文本匹配位置及其行内容摘要。
     *
     * @param path 工作空间相对文件路径
     * @param line 一基行号
     * @param column 一基列号
     * @param text 截断后的整行文本
     */
    public record Match(String path, int line, int column, String text) {
    }
}
