package com.yukina.codingagent.tool.builtin;

import com.yukina.codingagent.deepseek.DeepSeekToolDefinition;
import com.yukina.codingagent.tool.AgentTool;
import com.yukina.codingagent.tool.ToolExecutionException;
import com.yukina.codingagent.tool.command.EnvironmentProbeService;
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

    /**
     * 创建受控命令执行工具。
     *
     * @param pathResolver 将相对路径限制在当前工作空间内的解析器
     * @param properties 命令白名单、超时和输入输出上限配置
     * @param objectMapper 用于解析和序列化工具参数及结果
     */
    public ExecuteCommandTool(
            WorkspacePathResolver pathResolver,
            CommandProperties properties,
            ObjectMapper objectMapper
    ) {
        this.pathResolver = pathResolver;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 返回提供给模型的 {@code execute_command} 工具定义。
     *
     * @return 包含工具名称、用途和 JSON 参数结构的不可变定义
     */
    @Override
    public DeepSeekToolDefinition definition() {
        return DEFINITION;
    }

    /**
     * 校验命令、工作目录和超时后启动子进程并收集受限输出。
     *
     * @param arguments 工具调用参数，必须包含字符串数组 {@code command}
     * @return JSON 字符串形式的执行结果，包含退出码、输出、超时状态和耗时
     * @throws ToolExecutionException 参数无效、命令越界、程序不存在或进程执行失败时抛出
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
     *
     * @param arguments 完整工具参数；可以为 {@code null}，随后由统一参数校验报告错误
     * @return 第一个元素为程序名、其余元素为独立参数的非空命令列表
     * @throws ToolExecutionException 命令缺失、类型错误、为空或超过参数数量上限时抛出
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

    /**
     * 读取互斥的内联输入或工作空间输入文件，并执行字节上限校验。
     *
     * @param arguments 完整工具参数，可通过 {@code stdin} 或 {@code stdin_file} 提供输入
     * @return 发送给子进程的字节；未提供输入时返回空数组
     * @throws ToolExecutionException 两种输入同时出现、输入类型错误、文件无效或输入超限时抛出
     */
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

    /**
     * 校验标准输入大小并返回原字节。
     *
     * @param input 待发送给子进程的标准输入字节
     * @return 未超过配置上限的原始字节数组
     * @throws ToolExecutionException 输入超过 {@code maxInputBytes} 时抛出
     */
    private byte[] validateInputSize(byte[] input) {
        if (input.length > properties.maxInputBytes()) {
            throw inputTooLarge();
        }
        return input;
    }

    /**
     * 创建统一的标准输入超限错误。
     *
     * @return 错误码为 {@code INPUT_TOO_LARGE} 的工具执行异常
     */
    private ToolExecutionException inputTooLarge() {
        return new ToolExecutionException(
                "INPUT_TOO_LARGE",
                "Standard input exceeds the limit of " + properties.maxInputBytes() + " bytes"
        );
    }

    /**
     * 限制程序来源、Shell 元字符和具有写入能力的 Git 子命令。
     *
     * @param command 已解析的非空命令列表
     * @throws ToolExecutionException 程序不在白名单、参数越界或命令触发安全限制时抛出
     */
    private void validateCommand(List<String> command) {
        String rawExecutable = command.getFirst();
        String normalizedPath = rawExecutable.replace('\\', '/');
        boolean workspaceExecutable = isWorkspaceExecutable(normalizedPath);
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

    /**
     * 拒绝命令参数中最直接的绝对路径和父目录穿越。
     *
     * @param argument 单个命令参数；URL 不作为本地路径处理
     * @throws ToolExecutionException 参数引用绝对路径或父目录时抛出
     */
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
     *
     * @param command 原始命令列表，第一个元素为待解析的程序名
     * @param workingDirectory 命令工作目录，也是项目 Wrapper 和 {@code ./程序} 的查找起点
     * @return 可直接传给 {@link ProcessBuilder} 的命令列表
     * @throws ToolExecutionException 找不到可执行程序时抛出
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
     *
     * @param rawExecutable 模型提供的程序名或工作空间相对程序名
     * @param workingDirectory 当前命令的工作目录
     * @return 已规范化的真实程序绝对路径
     * @throws ToolExecutionException 工作空间程序或宿主程序均未找到时抛出
     */
    private Path resolveExecutable(String rawExecutable, Path workingDirectory) {
        String normalized = rawExecutable.replace('\\', '/');
        if (normalized.startsWith("./")) {
            String localName = normalized.substring(2);
            for (String extension : executableExtensions(localName)) {
                Path local = workingDirectory.resolve(localName + extension).normalize();
                if (local.startsWith(pathResolver.root())
                        && Files.isRegularFile(local, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(local)) {
                    return local;
                }
            }
            throw new ToolExecutionException("COMMAND_NOT_FOUND", "Workspace command was not found: " + rawExecutable);
        }

        if (isProjectWrapperName(rawExecutable)) {
            for (String extension : executableExtensions(rawExecutable)) {
                Path wrapper = workingDirectory.resolve(rawExecutable + extension).normalize();
                if (wrapper.startsWith(pathResolver.root())
                        && Files.isRegularFile(wrapper, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(wrapper)) {
                    return wrapper;
                }
            }
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
        String executable = CommandProperties.normalizeExecutable(rawExecutable);
        throw new ToolExecutionException(
                "COMMAND_NOT_FOUND",
                "Executable was not found in configured search paths or PATH: " + rawExecutable,
                Map.of(
                        "executable", executable,
                        "recoverable", true,
                        "installHint", EnvironmentProbeService.installHintForCommand(executable)
                )
        );
    }

    /**
     * 在一个受信任目录中按平台扩展名顺序查找程序。
     *
     * @param directory 配置目录或 PATH 中的单个目录
     * @param executable 不含目录部分的程序名
     * @param extensions 按优先级排列的候选扩展名
     * @return 找到的规范化绝对路径；没有匹配文件时返回 {@code null}
     */
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

    /**
     * 返回当前平台查找程序时使用的扩展名顺序。
     *
     * @param executable 原始程序名，可能已经包含 Windows 扩展名
     * @return Unix 上为单个空扩展名；Windows 上通常为 exe、cmd、bat、com 顺序
     */
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
     * 配置的搜索目录会被放在保留 PATH 的最前面。
     *
     * @param environment {@link ProcessBuilder} 的可变环境变量映射，将被原地清理和重建
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
     *
     * @param inputStream 子进程的 stdout 或 stderr 输入流
     * @param limit 最多保留的字符数量；超过部分仍会读取但不再保存
     * @return 捕获内容及是否发生截断
     * @throws IOException 读取子进程输出失败时抛出
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

    /**
     * 写入标准输入并始终关闭管道，使子进程能够收到 EOF。
     *
     * @param process 正在运行的子进程
     * @param input 要写入其标准输入的字节
     */
    private static void provideStandardInput(Process process, byte[] input) {
        try (OutputStream output = process.getOutputStream()) {
            output.write(input);
            output.flush();
        } catch (IOException ignored) {
            // 子进程可能在读取全部输入前正常退出，此时关闭的管道无需升级为工具失败。
        }
    }

    /**
     * 等待标准输入写入任务结束。
     *
     * @param input 异步标准输入写入任务
     * @throws InterruptedException 当前线程等待时被中断
     * @throws ToolExecutionException 异步写入任务执行失败时抛出
     */
    private static void awaitInput(Future<?> input) throws InterruptedException {
        try {
            input.get();
        } catch (ExecutionException exception) {
            throw new ToolExecutionException("COMMAND_INPUT_FAILED", "Failed to provide command input");
        }
    }

    /**
     * 等待输出读取任务，并统一转换异步读取错误。
     *
     * @param output 异步输出捕获任务
     * @return 捕获到的受限输出
     * @throws InterruptedException 当前线程等待时被中断
     * @throws ToolExecutionException 异步读取任务执行失败时抛出
     */
    private static CapturedOutput await(Future<CapturedOutput> output) throws InterruptedException {
        try {
            return output.get();
        } catch (ExecutionException exception) {
            throw new ToolExecutionException("COMMAND_OUTPUT_FAILED", "Failed to capture command output");
        }
    }

    /**
     * 先终止子进程树，超过宽限时间后再强制结束。
     *
     * @param process 要终止的根进程
     * @throws InterruptedException 等待进程在宽限期内退出时当前线程被中断
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

    /**
     * 在当前线程被中断时尽力终止进程树。
     *
     * @param process 要尽力终止的根进程
     */
    private void terminateQuietly(Process process) {
        try {
            terminate(process);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 将命令结果序列化为可回传给模型的结构化 JSON。
     *
     * @param command 用户请求的原始命令列表
     * @param workingDirectory 实际工作目录
     * @param stdinBytes 写入标准输入的字节数
     * @param exitCode 进程退出码；超时时为 {@code null}
     * @param timedOut 命令是否因超过时限而被终止
     * @param stdout 标准输出及截断状态
     * @param stderr 标准错误及截断状态
     * @param duration 命令从启动到完成清理的总耗时
     * @return 可直接返回给模型的 JSON 字符串
     */
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

    /**
     * 判断当前 JVM 是否运行在 Windows。
     *
     * @return Windows 系统返回 {@code true}，否则返回 {@code false}
     */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 判断显式相对路径是否属于允许执行的工作空间程序。
     * Windows 仅允许 exe、com 和固定项目 Wrapper，不开放其他批处理脚本。
     *
     * @param normalizedPath 已将反斜杠转换为正斜杠的程序路径
     * @return 路径形如 {@code ./文件名} 且文件类型受支持时返回 {@code true}
     */
    private static boolean isWorkspaceExecutable(String normalizedPath) {
        if (!normalizedPath.startsWith("./") || normalizedPath.indexOf('/', 2) >= 0) {
            return false;
        }
        if (isProjectWrapperName(normalizedPath.substring(2))) {
            return true;
        }
        if (!isWindows()) {
            return true;
        }
        String lowerCase = normalizedPath.toLowerCase(Locale.ROOT);
        return lowerCase.endsWith(".exe") || lowerCase.endsWith(".com");
    }

    /**
     * 判断文件名是否为 Maven 或 Gradle 官方项目 Wrapper。
     *
     * @param name 程序文件名，可以带 Windows 可执行扩展名
     * @return 规范化名称为 mvnw 或 gradlew 时返回 {@code true}
     */
    private static boolean isProjectWrapperName(String name) {
        String normalized = CommandProperties.normalizeExecutable(name);
        return "mvnw".equals(normalized) || "gradlew".equals(normalized);
    }

    /**
     * 检查会被 cmd.exe 解释的高风险字符。
     *
     * @param argument 单个命令参数
     * @return 参数包含受限制的 Shell 元字符时返回 {@code true}
     */
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

    /**
     * 对传给 cmd.exe 的批处理参数进行保守引用。
     *
     * @param argument 已通过 Shell 元字符校验的参数
     * @return 使用双引号包围的参数
     */
    private static String quoteWindowsArgument(String argument) {
        return '"' + argument + '"';
    }

    /**
     * 返回 Windows 系统命令解释器路径。
     *
     * @return {@code ComSpec} 环境变量值；未配置时返回 {@code cmd.exe}
     */
    private static String systemCommandInterpreter() {
        String comSpec = System.getenv("ComSpec");
        return comSpec == null || comSpec.isBlank() ? "cmd.exe" : comSpec;
    }

    /**
     * 保存受长度限制的单个输出流。
     *
     * @param content 实际保留的输出文本
     * @param truncated 是否因长度上限丢弃了后续文本
     */
    private record CapturedOutput(String content, boolean truncated) {
    }
}
