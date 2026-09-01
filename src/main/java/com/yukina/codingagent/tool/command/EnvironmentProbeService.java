package com.yukina.codingagent.tool.command;

import com.yukina.codingagent.tool.workspace.WorkspaceExecutionContext;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 检测应用进程实际可见的开发工具，并为界面和 Agent 复用同一份能力快照。
 */
@Service
public class EnvironmentProbeService implements ExecutionEnvironmentProvider {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);
    private static final int MAX_VERSION_CHARS = 180;
    private static final Set<String> SAFE_ENVIRONMENT_VARIABLES = Set.of(
            "COMSPEC", "HOME", "JAVA_HOME", "LANG", "LC_ALL", "PATH", "PATHEXT",
            "SYSTEMROOT", "TEMP", "TMP", "USERPROFILE"
    );
    private static final List<ToolSpec> TOOL_SPECS = List.of(
            spec("java", "Java", List.of("java"), List.of("--version"),
                    "请安装 Java 21 或更高版本的 JDK，并将 bin 目录加入 PATH。"),
            spec("javac", "Java 编译器", List.of("javac"), List.of("--version"),
                    "请安装 Java 21 或更高版本的 JDK；仅安装 JRE 不包含 javac。"),
            spec("maven", "Maven", List.of("mvn"), List.of("--version"),
                    "请安装 Maven，或在项目中提供 Maven Wrapper。"),
            spec("gradle", "Gradle", List.of("gradle"), List.of("--version"),
                    "请安装 Gradle，或在项目中提供 Gradle Wrapper。"),
            spec("node", "Node.js", List.of("node"), List.of("--version"),
                    "请安装当前 Node.js LTS 版本，然后重启 Coding Agent。"),
            spec("npm", "npm", List.of("npm"), List.of("--version"),
                    "请安装包含 npm 的 Node.js，然后重启 Coding Agent。"),
            spec("python", "Python", List.of("python", "python3"), List.of("--version"),
                    "请安装 Python 3 并将其加入 PATH。"),
            spec("pytest", "pytest", List.of("pytest"), List.of("--version"),
                    "请在当前 Python 环境中安装 pytest。"),
            spec("cpp", "C++ 编译器", List.of("g++", "clang++"), List.of("--version"),
                    "请安装 MinGW-w64 G++ 或 Clang，并将 bin 目录加入 PATH。"),
            spec("c", "C 编译器", List.of("gcc", "clang"), List.of("--version"),
                    "请安装 GCC 或 Clang，并将 bin 目录加入 PATH。"),
            spec("cmake", "CMake", List.of("cmake"), List.of("--version"),
                    "请安装 CMake 并将其加入 PATH。"),
            spec("go", "Go", List.of("go"), List.of("version"),
                    "请安装 Go SDK 并将其加入 PATH。"),
            spec("rust", "Rust", List.of("cargo", "rustc"), List.of("--version"),
                    "请通过 rustup 安装 Rust，然后重启 Coding Agent。"),
            spec("dotnet", ".NET SDK", List.of("dotnet"), List.of("--version"),
                    "请安装 .NET SDK 并将其加入 PATH。")
    );

    private final CommandProperties properties;
    private final WorkspaceExecutionContext workspaceExecutionContext;
    private final Object cacheMonitor = new Object();
    private volatile HostSnapshot cachedHostSnapshot;

    /**
     * 创建复用命令白名单和工作空间上下文的环境检测服务。
     *
     * @param properties 命令白名单和额外程序搜索目录配置
     * @param workspaceExecutionContext 当前线程绑定的工作空间上下文
     */
    public EnvironmentProbeService(
            CommandProperties properties,
            WorkspaceExecutionContext workspaceExecutionContext
    ) {
        this.properties = properties;
        this.workspaceExecutionContext = workspaceExecutionContext;
    }

    /**
     * 返回主机环境，并叠加指定项目根目录中的 Maven/Gradle Wrapper。
     *
     * @param workspaceRoot 项目根目录；为 {@code null} 时只返回宿主环境
     * @param refresh 是否忽略主机缓存并重新执行版本检测
     * @return 不包含本地绝对路径的环境状态快照
     */
    public EnvironmentSnapshot inspect(Path workspaceRoot, boolean refresh) {
        HostSnapshot host = hostSnapshot(refresh);
        List<EnvironmentToolStatus> tools = new ArrayList<>(host.tools());
        if (workspaceRoot != null) {
            applyProjectWrapper(tools, workspaceRoot, "maven", "Maven", "mvnw");
            applyProjectWrapper(tools, workspaceRoot, "gradle", "Gradle", "gradlew");
        }
        return new EnvironmentSnapshot(host.checkedAt(), tools);
    }

    /**
     * 清除主机缓存并立即重新检测。
     *
     * @param workspaceRoot 要叠加 Wrapper 状态的项目根目录；可以为 {@code null}
     * @return 重新检测得到的环境状态快照
     */
    public EnvironmentSnapshot refresh(Path workspaceRoot) {
        return inspect(workspaceRoot, true);
    }

    /**
     * 生成 Agent 可直接使用且不含本地绝对路径的紧凑能力说明。
     *
     * @return 列出可用命令、版本和不可用工具的英文提示词片段
     */
    @Override
    public String agentSummary() {
        EnvironmentSnapshot snapshot = inspect(workspaceExecutionContext.root(), false);
        List<String> available = snapshot.tools().stream()
                .filter(EnvironmentToolStatus::available)
                .map(tool -> tool.command() + versionSuffix(tool.version()))
                .toList();
        List<String> unavailable = snapshot.tools().stream()
                .filter(tool -> !tool.available())
                .map(EnvironmentToolStatus::name)
                .toList();
        return "Detected execution environment. Use only exact available command names and do not repeatedly call "
                + "missing executables. Available: "
                + (available.isEmpty() ? "none" : String.join(", ", available))
                + ". Unavailable: "
                + (unavailable.isEmpty() ? "none" : String.join(", ", unavailable))
                + ". If verification needs an unavailable tool, continue safe file work and clearly report that "
                + "the verification was skipped.";
    }

    /**
     * 返回命令缺失时可展示给模型和用户的恢复建议。
     *
     * @param executable 缺失的程序名，可以包含 Windows 可执行扩展名
     * @return 已知工具的专用安装建议，或通用 PATH 配置建议
     */
    public static String installHintForCommand(String executable) {
        String normalized = CommandProperties.normalizeExecutable(executable);
        return TOOL_SPECS.stream()
                .filter(spec -> spec.commands().stream().anyMatch(normalized::equals))
                .map(ToolSpec::installHint)
                .findFirst()
                .orElse("请安装 " + normalized + " 并将可执行文件目录加入 PATH，然后重启 Coding Agent。");
    }

    /**
     * 延迟构建主机快照，避免每轮 Agent 调用重复启动版本探测进程。
     *
     * @param refresh 是否强制丢弃现有缓存
     * @return 缓存或刚刚生成的不可变主机快照
     */
    private HostSnapshot hostSnapshot(boolean refresh) {
        HostSnapshot current = cachedHostSnapshot;
        if (!refresh && current != null) {
            return current;
        }
        synchronized (cacheMonitor) {
            if (!refresh && cachedHostSnapshot != null) {
                return cachedHostSnapshot;
            }
            List<EnvironmentToolStatus> tools = TOOL_SPECS.stream().map(this::probe).toList();
            cachedHostSnapshot = new HostSnapshot(Instant.now(), tools);
            return cachedHostSnapshot;
        }
    }

    /**
     * 检测一类工具的第一个可用候选程序。
     *
     * @param spec 工具名称、候选命令、版本参数和安装提示定义
     * @return 第一个通过白名单、路径查找和版本命令验证的状态；均失败时返回不可用状态
     */
    private EnvironmentToolStatus probe(ToolSpec spec) {
        List<String> allowedCandidates = spec.commands().stream().filter(properties::isAllowed).toList();
        if (allowedCandidates.isEmpty()) {
            return unavailable(spec, "已被命令白名单禁用。", null);
        }
        String failedProbe = null;
        for (String command : allowedCandidates) {
            LocatedExecutable located = locateHost(command);
            if (located == null) {
                continue;
            }
            VersionResult version = readVersion(located.path(), spec.versionArguments());
            if (!version.usable()) {
                failedProbe = "已找到 " + command + "，但版本检测失败"
                        + (version.version() == null ? "。" : "：" + version.version());
                continue;
            }
            return new EnvironmentToolStatus(
                    spec.id(),
                    spec.name(),
                    true,
                    command,
                    version.version(),
                    located.source(),
                    version.message(),
                    null
            );
        }
        return unavailable(
                spec,
                failedProbe == null
                        ? "未在配置目录或系统 PATH 中找到可执行程序。"
                        : failedProbe,
                spec.installHint()
        );
    }

    /**
     * 项目 Wrapper 存在时覆盖同类全局工具，确保构建版本与项目保持一致。
     *
     * @param tools 待原地更新的工具状态列表
     * @param workspaceRoot 当前项目根目录
     * @param id 被覆盖工具的稳定标识
     * @param name 被覆盖工具的展示名称
     * @param baseName Wrapper 基础文件名，例如 mvnw 或 gradlew
     */
    private void applyProjectWrapper(
            List<EnvironmentToolStatus> tools,
            Path workspaceRoot,
            String id,
            String name,
            String baseName
    ) {
        if (!properties.isAllowed(baseName)) {
            return;
        }
        String fileName = findWrapper(workspaceRoot, baseName);
        if (fileName == null) {
            return;
        }
        EnvironmentToolStatus wrapper = new EnvironmentToolStatus(
                id,
                name,
                true,
                "./" + fileName,
                "项目 Wrapper",
                EnvironmentToolStatus.Source.PROJECT_WRAPPER,
                "优先使用项目 Wrapper，而不是全局安装版本。",
                null
        );
        for (int index = 0; index < tools.size(); index++) {
            if (id.equals(tools.get(index).id())) {
                tools.set(index, wrapper);
                return;
            }
        }
        tools.add(wrapper);
    }

    /**
     * 查找工作区根目录中的固定 Wrapper 文件，不执行项目脚本完成探测。
     *
     * @param workspaceRoot 项目根目录
     * @param baseName Wrapper 基础文件名
     * @return 找到的实际文件名；不存在、不规则或为符号链接时返回 {@code null}
     */
    private static String findWrapper(Path workspaceRoot, String baseName) {
        List<String> candidates = isWindows()
                ? List.of(baseName + ".cmd", baseName + ".bat", baseName)
                : List.of(baseName);
        Path normalizedRoot = workspaceRoot.toAbsolutePath().normalize();
        for (String candidate : candidates) {
            Path wrapper = normalizedRoot.resolve(candidate).normalize();
            if (wrapper.getParent().equals(normalizedRoot)
                    && Files.isRegularFile(wrapper, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(wrapper)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 按配置目录优先、系统 PATH 次之的顺序定位宿主程序。
     *
     * @param executable 不含目录部分的候选程序名
     * @return 程序绝对路径及来源；所有目录均未找到时返回 {@code null}
     */
    private LocatedExecutable locateHost(String executable) {
        for (Path directory : properties.searchPaths()) {
            Path found = findExecutable(directory, executable);
            if (found != null) {
                return new LocatedExecutable(found, EnvironmentToolStatus.Source.CONFIGURED_PATH);
            }
        }
        String pathValue = System.getenv("PATH");
        if (pathValue == null) {
            return null;
        }
        for (String directory : pathValue.split(java.io.File.pathSeparator)) {
            if (directory.isBlank()) {
                continue;
            }
            try {
                Path found = findExecutable(Path.of(directory), executable);
                if (found != null) {
                    return new LocatedExecutable(found, EnvironmentToolStatus.Source.SYSTEM_PATH);
                }
            } catch (InvalidPathException ignored) {
                // 跳过当前进程继承的无效 PATH 项。
            }
        }
        return null;
    }

    /**
     * 在单个目录中按当前平台扩展名查找程序。
     *
     * @param directory 配置目录或 PATH 中的单个目录
     * @param executable 不含目录部分的程序名
     * @return 找到的规范化绝对路径；没有匹配文件时返回 {@code null}
     */
    private static Path findExecutable(Path directory, String executable) {
        for (String extension : executableExtensions(executable)) {
            try {
                Path candidate = directory.resolve(executable + extension);
                if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    return candidate.toAbsolutePath().normalize();
                }
            } catch (InvalidPathException ignored) {
                // 继续检查其余目录。
            }
        }
        return null;
    }

    /**
     * 启动可信宿主工具的版本命令并限制执行时间。
     *
     * @param executable 已定位的宿主程序绝对路径
     * @param arguments 固定的版本查询参数
     * @return 版本首行、补充消息和可用性；启动失败、超时及中断也转换为结果
     */
    private VersionResult readVersion(Path executable, List<String> arguments) {
        List<String> command = launchCommand(executable, arguments);
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            sanitizeEnvironment(builder.environment());
            process = builder.start();
            boolean finished = process.waitFor(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor();
                return new VersionResult(null, "版本检测超时。", false);
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String version = firstNonBlankLine(output);
            if (version == null) {
                return new VersionResult(null, "未返回版本信息。", process.exitValue() == 0);
            }
            boolean usable = process.exitValue() == 0;
            return new VersionResult(
                    version,
                    usable ? null : "版本命令返回了非零退出码。",
                    usable
            );
        } catch (IOException exception) {
            return new VersionResult(null, "无法启动版本检测。", false);
        } catch (InterruptedException exception) {
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            return new VersionResult(null, "版本检测被中断。", false);
        }
    }

    /**
     * 仅为 Windows 批处理程序使用系统解释器，参数全部来自固定探测定义。
     *
     * @param executable 已定位的程序绝对路径
     * @param arguments 固定版本查询参数
     * @return 可直接传给 {@link ProcessBuilder} 的命令列表
     */
    private static List<String> launchCommand(Path executable, List<String> arguments) {
        List<String> direct = new ArrayList<>();
        direct.add(executable.toString());
        direct.addAll(arguments);
        String lowerCase = executable.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!isWindows() || !(lowerCase.endsWith(".cmd") || lowerCase.endsWith(".bat"))) {
            return List.copyOf(direct);
        }
        String joined = direct.stream()
                .map(EnvironmentProbeService::quoteWindowsArgument)
                .reduce((left, right) -> left + " " + right)
                .orElseThrow();
        return List.of(systemCommandInterpreter(), "/d", "/s", "/c", '"' + joined + '"');
    }

    /**
     * 仅向探测进程保留构建工具识别版本所需的环境变量。
     *
     * @param environment {@link ProcessBuilder} 的可变环境变量映射，将被原地清理
     */
    private static void sanitizeEnvironment(Map<String, String> environment) {
        Map<String, String> inherited = new HashMap<>(environment);
        environment.clear();
        inherited.forEach((name, value) -> {
            if (SAFE_ENVIRONMENT_VARIABLES.contains(name.toUpperCase(Locale.ROOT))) {
                environment.put(name, value);
            }
        });
        environment.put("NO_COLOR", "1");
    }

    /**
     * 返回一行受限长度的版本信息。
     *
     * @param output 版本命令合并后的标准输出和标准错误
     * @return 第一条非空行；没有内容时返回 {@code null}，过长时截断
     */
    private static String firstNonBlankLine(String output) {
        if (output == null) {
            return null;
        }
        return output.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .findFirst()
                .map(line -> line.length() <= MAX_VERSION_CHARS ? line : line.substring(0, MAX_VERSION_CHARS))
                .orElse(null);
    }

    /**
     * 根据 Windows PATHEXT 约定生成候选扩展名。
     *
     * @param executable 原始程序名，可能已经包含扩展名
     * @return 按系统优先级排列的扩展名；非 Windows 或已有扩展名时只包含空字符串
     */
    private static List<String> executableExtensions(String executable) {
        if (!isWindows() || executable.contains(".")) {
            return List.of("");
        }
        String pathExt = System.getenv("PATHEXT");
        if (pathExt == null || pathExt.isBlank()) {
            return List.of(".exe", ".cmd", ".bat", ".com");
        }
        List<String> extensions = new ArrayList<>();
        for (String extension : pathExt.split(";")) {
            String normalized = extension.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isBlank() && !extensions.contains(normalized)) {
                extensions.add(normalized);
            }
        }
        return extensions.isEmpty() ? List.of(".exe", ".cmd", ".bat", ".com") : List.copyOf(extensions);
    }

    /**
     * 为 cmd.exe 安全引用单个固定参数。
     *
     * @param argument 固定探测命令中的单个参数
     * @return 转义内部双引号并使用双引号包围的参数
     */
    private static String quoteWindowsArgument(String argument) {
        return '"' + argument.replace("\"", "\"\"") + '"';
    }

    /**
     * 返回当前 Windows 系统解释器。
     *
     * @return {@code COMSPEC} 环境变量值；未配置时返回 {@code cmd.exe}
     */
    private static String systemCommandInterpreter() {
        String comSpec = System.getenv("COMSPEC");
        return comSpec == null || comSpec.isBlank() ? "cmd.exe" : comSpec;
    }

    /**
     * 判断当前 JVM 是否运行于 Windows。
     *
     * @return Windows 系统返回 {@code true}，否则返回 {@code false}
     */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 构造不可变工具检测定义。
     *
     * @param id 稳定工具标识
     * @param name 前端展示名称
     * @param commands 按优先级排列的候选程序名
     * @param versionArguments 版本命令参数
     * @param installHint 工具缺失时的安装建议
     * @return 对候选命令和参数进行防御性复制后的工具定义
     */
    private static ToolSpec spec(
            String id,
            String name,
            List<String> commands,
            List<String> versionArguments,
            String installHint
    ) {
        return new ToolSpec(id, name, List.copyOf(commands), List.copyOf(versionArguments), installHint);
    }

    /**
     * 构造不可用状态。
     *
     * @param spec 对应的工具检测定义
     * @param message 本次检测失败原因
     * @param installHint 可选安装建议
     * @return 来源标记为 {@code UNAVAILABLE} 的工具状态
     */
    private static EnvironmentToolStatus unavailable(ToolSpec spec, String message, String installHint) {
        return new EnvironmentToolStatus(
                spec.id(), spec.name(), false, null, null,
                EnvironmentToolStatus.Source.UNAVAILABLE, message, installHint
        );
    }

    /**
     * 在摘要中附加简短版本，不重复输出项目 Wrapper 文案。
     *
     * @param version 原始版本首行或项目 Wrapper 标记
     * @return 方括号包围的紧凑版本；没有有效版本时返回空字符串
     */
    private static String versionSuffix(String version) {
        if (version == null || version.isBlank() || "项目 Wrapper".equals(version)) {
            return "";
        }
        String compact = version.length() <= 60 ? version : version.substring(0, 60);
        return " [" + compact + "]";
    }

    /**
     * 描述一类开发工具及其版本探测方式。
     *
     * @param id 稳定工具标识
     * @param name 前端展示名称
     * @param commands 按优先级排列的候选程序名
     * @param versionArguments 版本查询参数
     * @param installHint 工具缺失时的安装建议
     */
    private record ToolSpec(
            String id,
            String name,
            List<String> commands,
            List<String> versionArguments,
            String installHint
    ) {
    }

    /**
     * 保存已经定位的程序及其来源。
     *
     * @param path 程序的规范化绝对路径
     * @param source 配置目录或系统 PATH 来源
     */
    private record LocatedExecutable(Path path, EnvironmentToolStatus.Source source) {
    }

    /**
     * 保存一次版本命令的探测结果。
     *
     * @param version 版本输出首行；无法读取时为 {@code null}
     * @param message 超时、失败等补充说明；成功时通常为 {@code null}
     * @param usable 版本命令是否成功退出
     */
    private record VersionResult(String version, String message, boolean usable) {
    }

    /**
     * 缓存不含项目 Wrapper 的宿主环境探测结果。
     *
     * @param checkedAt 快照生成时间
     * @param tools 不可变工具状态列表
     */
    private record HostSnapshot(Instant checkedAt, List<EnvironmentToolStatus> tools) {

        /** 对工具列表进行防御性复制，避免缓存被调用方修改。 */
        private HostSnapshot {
            tools = List.copyOf(tools);
        }
    }
}
