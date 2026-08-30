package com.yukina.codingagent.tool.builtin;

import com.yukina.codingagent.deepseek.DeepSeekToolDefinition;
import com.yukina.codingagent.tool.AgentTool;
import com.yukina.codingagent.tool.ToolExecutionException;
import com.yukina.codingagent.tool.command.CommandProperties;
import com.yukina.codingagent.tool.workspace.ToolArguments;
import com.yukina.codingagent.tool.workspace.ToolJson;
import com.yukina.codingagent.tool.workspace.WorkspacePathResolver;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 在受限工作区中执行构建、测试和代码检查命令。
 */
@Component
public class ExecuteCommandTool implements AgentTool {

    private static final Set<String> SAFE_ENVIRONMENT_VARIABLES = Set.of(
            "COMSPEC",
            "HOME",
            "JAVA_HOME",
            "LANG",
            "LC_ALL",
            "PATH",
            "PATHEXT",
            "SYSTEMROOT",
            "TEMP",
            "TMP",
            "USERPROFILE"
    );
    private static final Set<String> READ_ONLY_GIT_SUBCOMMANDS = Set.of(
            "branch",
            "diff",
            "grep",
            "log",
            "ls-files",
            "rev-parse",
            "show",
            "status"
    );
    private static final Map<String, Set<String>> FORBIDDEN_INLINE_CODE_FLAGS = Map.of(
            "python", Set.of("-c"),
            "python3", Set.of("-c"),
            "node", Set.of("-e", "--eval", "-p", "--print")
    );
    private static final DeepSeekToolDefinition DEFINITION = DeepSeekToolDefinition.function(
            "execute_command",
            "Execute an allowlisted build, test, or inspection command inside the workspace. "
                    + "Pass the executable and each argument as a separate array item; shell operators are not supported. "
                    + "The command field must be a JSON array, never a JSON-encoded string. "
                    + "Compile and run in separate calls, using ./program.exe for a generated workspace executable on Windows.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "command", Map.of(
                                    "type", "array",
                                    "description", "Executable followed by separate arguments, for example [\"mvn\",\"test\"]",
                                    "items", Map.of("type", "string"),
                                    "minItems", 1
                            ),
                            "working_directory", Map.of(
                                    "type", "string",
                                    "description", "Relative workspace directory in which to run the command",
                                    "default", "."
                            ),
                            "timeout_seconds", Map.of(
                                    "type", "integer",
                                    "description", "Maximum execution time in seconds",
                                    "minimum", 1
                            ),
                            "stdin", Map.of(
                                    "type", "string",
                                    "description", "Optional UTF-8 standard input text; do not use shell redirection"
                            ),
                            "stdin_file", Map.of(
                                    "type", "string",
                                    "description", "Optional workspace-relative file to send to standard input"
                            )
                    ),
                    "required", List.of("command"),
                    "additionalProperties", false
            )
    );

    private final WorkspacePathResolver pathResolver;
    private final CommandProperties properties;
    private final ObjectMapper objectMapper;

    /** 创建受控命令执行工具。 */
    public ExecuteCommandTool(
            WorkspacePathResolver pathResolver,
            CommandProperties properties,
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
     * 校验命令、工作目录和超时后启动子进程并收集受限输出。
     */
    @Override
    public String execute(JsonNode arguments) {
        List<String> command = readCommand(arguments);
        validateCommand(command);
        byte[] standardInput = readStandardInput(arguments);

        Path workingDirectory = pathResolver.resolveExisting(
                ToolArguments.optionalText(arguments, "working_directory", ".")
        );
        if (!Files.isDirectory(workingDirectory)) {
            throw new ToolExecutionException(
                    "NOT_A_DIRECTORY",
                    "working_directory is not a directory: " + pathResolver.display(workingDirectory)
            );
        }
        int maxTimeoutSeconds = Math.toIntExact(properties.maxTimeout().toSeconds());
        int defaultTimeoutSeconds = Math.toIntExact(properties.defaultTimeout().toSeconds());
        Duration timeout = Duration.ofSeconds(ToolArguments.optionalInt(
                arguments,
                "timeout_seconds",
                defaultTimeoutSeconds,
                1,
                maxTimeoutSeconds
        ));

        long startedAt = System.nanoTime();
        Process process = null;
        try {
            List<String> launchCommand = createLaunchCommand(command, workingDirectory);
            ProcessBuilder processBuilder = new ProcessBuilder(launchCommand)
                    .directory(workingDirectory.toFile());
            sanitizeEnvironment(processBuilder.environment());
            process = processBuilder.start();
            Process runningProcess = process;

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<CapturedOutput> stdout = executor.submit(
                        () -> capture(runningProcess.getInputStream(), properties.maxOutputChars())
                );
                Future<CapturedOutput> stderr = executor.submit(
                        () -> capture(runningProcess.getErrorStream(), properties.maxOutputChars())
                );
                Future<?> stdin = executor.submit(() -> provideStandardInput(runningProcess, standardInput));

                boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!finished) {
                    terminate(process);
                }
                awaitInput(stdin);
                return serializeResult(
                        command,
                        workingDirectory,
                        standardInput.length,
                        finished ? process.exitValue() : null,
                        !finished,
                        await(stdout),
                        await(stderr),
                        Duration.ofNanos(System.nanoTime() - startedAt)
                );
            }
        } catch (InterruptedException exception) {
            if (process != null) {
                terminateQuietly(process);
            }
            Thread.currentThread().interrupt();
            throw new ToolExecutionException("COMMAND_INTERRUPTED", "Command execution was interrupted");
        } catch (IOException exception) {
            throw new ToolExecutionException(
                    "COMMAND_START_FAILED",
                    "Failed to start command " + command.getFirst() + ": " + exception.getMessage()
            );
        }
    }

    /**
     * 读取命令数组，并兼容模型偶尔生成的单层 JSON 字符串包装。
     * 普通 Shell 命令字符串不会被拆词或执行。
     */
    private List<String> readCommand(JsonNode arguments) {
        JsonNode command = arguments == null ? null : arguments.get("command");
        if (command != null && command.isTextual()) {
            String encoded = command.asText().trim();
            try {
                JsonNode decoded = objectMapper.readTree(encoded);
                if (decoded != null && decoded.isArray()) {
                    command = decoded;
                }
            } catch (JacksonException ignored) {
                // 保留原节点，由统一参数校验返回稳定错误。
            }
        }
        return ToolArguments.requiredTextListValue(command, "command", properties.maxArguments());
    }

    /** 读取互斥的内联输入或工作空间输入文件，并执行字节上限校验。 */
    private byte[] readStandardInput(JsonNode arguments) {
        JsonNode inline = arguments == null ? null : arguments.get("stdin");
        JsonNode file = arguments == null ? null : arguments.get("stdin_file");
        boolean hasInline = inline != null && !inline.isNull();
        boolean hasFile = file != null && !file.isNull();
        if (hasInline && hasFile) {
            throw new ToolExecutionException("INVALID_ARGUMENTS", "stdin and stdin_file are mutually exclusive");
        }
        if (hasInline) {
            if (!inline.isTextual()) {
                throw new ToolExecutionException("INVALID_ARGUMENTS", "stdin must be a string");
            }
            return validateInputSize(inline.asText().getBytes(StandardCharsets.UTF_8));
        }
        if (!hasFile) {
            return new byte[0];
        }
        if (!file.isTextual() || file.asText().isBlank()) {
            throw new ToolExecutionException("INVALID_ARGUMENTS", "stdin_file must be a non-blank string");
        }
        Path inputFile = pathResolver.resolveExisting(file.asText());
        if (Files.isSymbolicLink(inputFile)
                || !Files.isRegularFile(inputFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new ToolExecutionException("NOT_A_FILE", "stdin_file must be a regular workspace file");
        }
        try {
            if (Files.size(inputFile) > properties.maxInputBytes()) {
                throw inputTooLarge();
            }
            return validateInputSize(Files.readAllBytes(inputFile));
        } catch (IOException exception) {
            throw new ToolExecutionException("INPUT_READ_FAILED", "Unable to read stdin_file");
        }
    }

    /** 校验标准输入大小并返回原字节。 */
    private byte[] validateInputSize(byte[] input) {
        if (input.length > properties.maxInputBytes()) {
            throw inputTooLarge();
        }
        return input;
    }

    /** 创建统一的标准输入超限错误。 */
    private ToolExecutionException inputTooLarge() {
        return new ToolExecutionException(
                "INPUT_TOO_LARGE",
                "Standard input exceeds the limit of " + properties.maxInputBytes() + " bytes"
        );
    }

    /**
     * 限制程序来源、Shell 元字符和具有写入能力的 Git 子命令。
     */
    private void validateCommand(List<String> command) {
        String rawExecutable = command.getFirst();
        String normalizedPath = rawExecutable.replace('\\', '/');
        boolean workspaceExecutable = isWorkspaceNativeExecutable(normalizedPath);
        if (normalizedPath.contains("/")
                && !workspaceExecutable) {
            throw new ToolExecutionException(
                    "COMMAND_NOT_ALLOWED",
                    "Executable must be an allowlisted name or a workspace-root wrapper"
            );
        }
        String executable = CommandProperties.normalizeExecutable(rawExecutable);
        if (!workspaceExecutable && !properties.isAllowed(executable)) {
            throw new ToolExecutionException("COMMAND_NOT_ALLOWED", "Executable is not allowed: " + executable);
        }
        for (String argument : command) {
            if (argument.indexOf('\0') >= 0 || argument.indexOf('\r') >= 0 || argument.indexOf('\n') >= 0) {
                throw new ToolExecutionException("INVALID_ARGUMENTS", "Command arguments must not contain control characters");
            }
            if (isWindows() && containsWindowsShellMetacharacter(argument)) {
                throw new ToolExecutionException(
                        "INVALID_ARGUMENTS",
                        "Windows command arguments must not contain shell metacharacters"
                );
            }
        }
        for (int index = 1; index < command.size(); index++) {
            validateWorkspaceScopedArgument(command.get(index));
        }
        Set<String> forbiddenFlags = FORBIDDEN_INLINE_CODE_FLAGS.get(executable);
        if (forbiddenFlags != null && command.stream().skip(1).anyMatch(forbiddenFlags::contains)) {
            throw new ToolExecutionException(
                    "COMMAND_NOT_ALLOWED",
                    "Inline interpreter code is not allowed; write a workspace file and execute that file instead"
            );
        }
        if ("git".equals(executable)) {
            if (command.size() < 2 || !READ_ONLY_GIT_SUBCOMMANDS.contains(command.get(1).toLowerCase(Locale.ROOT))) {
                throw new ToolExecutionException(
                        "COMMAND_NOT_ALLOWED",
                        "Only read-only Git subcommands are allowed"
                );
            }
        }
    }

    /** 拒绝命令参数中最直接的绝对路径和父目录穿越。 */
    private static void validateWorkspaceScopedArgument(String argument) {
        String normalized = argument.replace('\\', '/');
        boolean url = normalized.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*");
        boolean windowsAbsolute = normalized.matches(".*[a-zA-Z]:/.*") || normalized.startsWith("//");
        boolean parentTraversal = normalized.equals("..")
                || normalized.startsWith("../")
                || normalized.endsWith("/..")
                || normalized.contains("/../");
        boolean posixAbsolute = !isWindows() && normalized.startsWith("/");
        if (!url && (windowsAbsolute || parentTraversal || posixAbsolute)) {
            throw new ToolExecutionException(
                    "PATH_OUTSIDE_WORKSPACE",
                    "Command arguments must not reference absolute paths or parent directories"
            );
        }
    }

    /**
     * 解析实际程序路径，并只为 Windows 批处理包装器启用 cmd.exe。
     */
    private List<String> createLaunchCommand(List<String> command, Path workingDirectory) {
        Path executable = resolveExecutable(command.getFirst(), workingDirectory);
        List<String> resolved = new ArrayList<>(command);
        resolved.set(0, executable.toString());
        String lowerCase = executable.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!isWindows() || !(lowerCase.endsWith(".cmd") || lowerCase.endsWith(".bat"))) {
            return List.copyOf(resolved);
        }

        String arguments = resolved.stream()
                .map(ExecuteCommandTool::quoteWindowsArgument)
                .reduce((left, right) -> left + " " + right)
                .orElseThrow();
        String commandLine = '"' + arguments + '"';
        return List.of(systemCommandInterpreter(), "/d", "/s", "/c", commandLine);
    }

    /**
     * 在工作目录和 PATH 中查找白名单程序，不接受任意绝对路径。
     */
    private Path resolveExecutable(String rawExecutable, Path workingDirectory) {
        String normalized = rawExecutable.replace('\\', '/');
        if (normalized.startsWith("./")) {
            String localName = normalized.substring(2);
            for (String extension : executableExtensions(localName)) {
                Path local = workingDirectory.resolve(localName + extension).normalize();
                if (local.startsWith(pathResolver.root())
                        && Files.isRegularFile(local, LinkOption.NOFOLLOW_LINKS)) {
                    return local;
                }
            }
            throw new ToolExecutionException("COMMAND_NOT_FOUND", "Workspace command was not found: " + rawExecutable);
        }

        List<String> extensions = executableExtensions(rawExecutable);
        for (Path directory : properties.searchPaths()) {
            Path resolved = findExecutable(directory, rawExecutable, extensions);
            if (resolved != null) {
                return resolved;
            }
        }
        String pathValue = System.getenv("PATH");
        if (pathValue != null) {
            for (String directory : pathValue.split(java.io.File.pathSeparator)) {
                if (directory.isBlank()) {
                    continue;
                }
                try {
                    Path resolved = findExecutable(Path.of(directory), rawExecutable, extensions);
                    if (resolved != null) {
                        return resolved;
                    }
                } catch (InvalidPathException ignored) {
                    // Ignore malformed inherited PATH entries and continue searching.
                }
            }
        }
        throw new ToolExecutionException(
                "COMMAND_NOT_FOUND",
                "Executable was not found in configured search paths or PATH: " + rawExecutable
        );
    }

    /** 在一个受信任目录中按平台扩展名顺序查找程序。 */
    private static Path findExecutable(Path directory, String executable, List<String> extensions) {
        for (String extension : extensions) {
            try {
                Path candidate = directory.resolve(executable + extension);
                if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    return candidate.toAbsolutePath().normalize();
                }
            } catch (InvalidPathException ignored) {
                // Ignore malformed configured or inherited PATH entries.
            }
        }
        return null;
    }

    /** 返回当前平台查找程序时使用的扩展名顺序。 */
    private static List<String> executableExtensions(String executable) {
        if (!isWindows()) {
            return List.of("");
        }
        String lowerCase = executable.toLowerCase(Locale.ROOT);
        if (lowerCase.endsWith(".exe")
                || lowerCase.endsWith(".cmd")
                || lowerCase.endsWith(".bat")
                || lowerCase.endsWith(".com")) {
            return List.of("");
        }
        return List.of(".exe", ".cmd", ".bat", ".com");
    }

    /**
     * 仅向子进程复制运行构建工具所需的非敏感环境变量。
     */
    private void sanitizeEnvironment(Map<String, String> environment) {
        Map<String, String> inherited = new HashMap<>(environment);
        environment.clear();
        inherited.forEach((name, value) -> {
            if (SAFE_ENVIRONMENT_VARIABLES.contains(name.toUpperCase(Locale.ROOT))) {
                environment.put(name, value);
            }
        });
        if (!properties.searchPaths().isEmpty()) {
            String pathKey = environment.keySet().stream()
                    .filter(name -> "PATH".equalsIgnoreCase(name))
                    .findFirst()
                    .orElse("PATH");
            String configuredPaths = String.join(
                    java.io.File.pathSeparator,
                    properties.searchPaths().stream().map(Path::toString).toList()
            );
            String inheritedPath = environment.get(pathKey);
            environment.put(
                    pathKey,
                    inheritedPath == null || inheritedPath.isBlank()
                            ? configuredPaths
                            : configuredPaths + java.io.File.pathSeparator + inheritedPath
            );
        }
        environment.put("NO_COLOR", "1");
    }

    /**
     * 持续排空进程输出，但只保留配置允许的前若干字符。
     */
    private static CapturedOutput capture(InputStream inputStream, int limit) throws IOException {
        StringBuilder content = new StringBuilder(Math.min(limit, 4096));
        boolean truncated = false;
        try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            char[] buffer = new char[2048];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                int remaining = limit - content.length();
                if (remaining > 0) {
                    content.append(buffer, 0, Math.min(remaining, count));
                }
                if (count > remaining) {
                    truncated = true;
                }
            }
        }
        return new CapturedOutput(content.toString(), truncated);
    }

    /** 写入标准输入并始终关闭管道，使子进程能够收到 EOF。 */
    private static void provideStandardInput(Process process, byte[] input) {
        try (OutputStream output = process.getOutputStream()) {
            output.write(input);
            output.flush();
        } catch (IOException ignored) {
            // 子进程可能在读取全部输入前正常退出，此时关闭的管道无需升级为工具失败。
        }
    }

    /** 等待标准输入写入任务结束。 */
    private static void awaitInput(Future<?> input) throws InterruptedException {
        try {
            input.get();
        } catch (ExecutionException exception) {
            throw new ToolExecutionException("COMMAND_INPUT_FAILED", "Failed to provide command input");
        }
    }

    /** 等待输出读取任务，并统一转换异步读取错误。 */
    private static CapturedOutput await(Future<CapturedOutput> output) throws InterruptedException {
        try {
            return output.get();
        } catch (ExecutionException exception) {
            throw new ToolExecutionException("COMMAND_OUTPUT_FAILED", "Failed to capture command output");
        }
    }

    /**
     * 先终止子进程树，超过宽限时间后再强制结束。
     */
    private void terminate(Process process) throws InterruptedException {
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        if (!process.waitFor(properties.terminationGrace().toMillis(), TimeUnit.MILLISECONDS)) {
            descendants.forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor();
        }
    }

    /** 在当前线程被中断时尽力终止进程树。 */
    private void terminateQuietly(Process process) {
        try {
            terminate(process);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** 将命令结果序列化为可回传给模型的结构化 JSON。 */
    private String serializeResult(
            List<String> command,
            Path workingDirectory,
            int stdinBytes,
            Integer exitCode,
            boolean timedOut,
            CapturedOutput stdout,
            CapturedOutput stderr,
            Duration duration
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("command", command);
        result.put("workingDirectory", pathResolver.display(workingDirectory));
        result.put("stdinBytes", stdinBytes);
        result.put("exitCode", exitCode);
        result.put("timedOut", timedOut);
        result.put("stdout", stdout.content());
        result.put("stdoutTruncated", stdout.truncated());
        result.put("stderr", stderr.content());
        result.put("stderrTruncated", stderr.truncated());
        result.put("durationMillis", duration.toMillis());
        return ToolJson.serialize(objectMapper, result);
    }

    /** 判断当前 JVM 是否运行在 Windows。 */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** 仅允许直接运行工作空间根目录中的原生程序，不允许批处理脚本绕过 Shell 限制。 */
    private static boolean isWorkspaceNativeExecutable(String normalizedPath) {
        if (!normalizedPath.startsWith("./") || normalizedPath.indexOf('/', 2) >= 0) {
            return false;
        }
        if (!isWindows()) {
            return true;
        }
        String lowerCase = normalizedPath.toLowerCase(Locale.ROOT);
        return lowerCase.endsWith(".exe") || lowerCase.endsWith(".com");
    }

    /** 检查会被 cmd.exe 解释的高风险字符。 */
    private static boolean containsWindowsShellMetacharacter(String argument) {
        return argument.indexOf('&') >= 0
                || argument.indexOf('|') >= 0
                || argument.indexOf('<') >= 0
                || argument.indexOf('>') >= 0
                || argument.indexOf('^') >= 0
                || argument.indexOf('%') >= 0
                || argument.indexOf('!') >= 0
                || argument.indexOf('"') >= 0;
    }

    /** 对传给 cmd.exe 的批处理参数进行保守引用。 */
    private static String quoteWindowsArgument(String argument) {
        return '"' + argument + '"';
    }

    /** 返回 Windows 系统命令解释器路径。 */
    private static String systemCommandInterpreter() {
        String comSpec = System.getenv("ComSpec");
        return comSpec == null || comSpec.isBlank() ? "cmd.exe" : comSpec;
    }

    /** 保存受长度限制的单个输出流。 */
    private record CapturedOutput(String content, boolean truncated) {
    }
}
