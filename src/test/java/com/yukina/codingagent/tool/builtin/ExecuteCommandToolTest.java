package com.yukina.codingagent.tool.builtin;

import com.yukina.codingagent.tool.ToolExecutionException;
import com.yukina.codingagent.tool.command.CommandProperties;
import com.yukina.codingagent.tool.workspace.WorkspacePathResolver;
import com.yukina.codingagent.tool.workspace.WorkspaceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 验证受控命令执行、资源边界和安全策略。 */
class ExecuteCommandToolTest {

    @TempDir
    Path workspace;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ExecuteCommandTool commandTool;

    /** 为每个用例创建隔离工作区和短时限命令工具。 */
    @BeforeEach
    void setUp() {
        WorkspaceProperties workspaceProperties = new WorkspaceProperties(
                workspace,
                1024,
                1024,
                100,
                50,
                1024,
                5
        );
        CommandProperties commandProperties = new CommandProperties(
                Duration.ofSeconds(3),
                Duration.ofSeconds(5),
                Duration.ofMillis(500),
                64,
                16,
                1024,
                List.of("java", "git", "mvn", "mvnw", "gradlew"),
                List.of()
        );
        commandTool = new ExecuteCommandTool(
                new WorkspacePathResolver(workspaceProperties),
                commandProperties,
                objectMapper
        );
    }

    /** 验证命令在指定工作目录中执行并返回标准输出和退出码。 */
    @Test
    void executesCommandInsideWorkspace() throws Exception {
        Path project = Files.createDirectories(workspace.resolve("project"));
        Files.writeString(project.resolve("Hello.java"), """
                class Hello {
                    public static void main(String[] args) {
                        System.out.print("hello-command");
                    }
                }
                """);

        JsonNode result = execute("""
                {
                  "command": ["java", "Hello.java"],
                  "working_directory": "project"
                }
                """);

        assertEquals(0, result.path("exitCode").asInt());
        assertFalse(result.path("timedOut").asBoolean());
        assertEquals("hello-command", result.path("stdout").asText());
        assertEquals("project", result.path("workingDirectory").asText());
    }

    /** 验证内联标准输入会写入子进程并在末尾发送 EOF。 */
    @Test
    void providesInlineStandardInput() throws Exception {
        Files.writeString(workspace.resolve("EchoInput.java"), """
                class EchoInput {
                    public static void main(String[] args) throws Exception {
                        System.out.print(new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
                """);

        JsonNode result = execute("""
                {"command":["java","EchoInput.java"],"stdin":"first line\\nsecond line\\n"}
                """);

        assertEquals(0, result.path("exitCode").asInt(), result::toPrettyString);
        assertEquals("first line\nsecond line\n", result.path("stdout").asText());
        assertEquals(23, result.path("stdinBytes").asInt());
    }

    /** 验证工作空间文件可以直接作为标准输入，不需要 Shell 重定向。 */
    @Test
    void providesWorkspaceFileAsStandardInput() throws Exception {
        Files.writeString(workspace.resolve("ReadNumber.java"), """
                class ReadNumber {
                    public static void main(String[] args) {
                        java.util.Scanner scanner = new java.util.Scanner(System.in);
                        System.out.print(scanner.nextInt() * 2);
                    }
                }
                """);
        Files.writeString(workspace.resolve("input.txt"), "21\n");

        JsonNode result = execute("""
                {"command":["java","ReadNumber.java"],"stdin_file":"input.txt"}
                """);

        assertEquals(0, result.path("exitCode").asInt(), result::toPrettyString);
        assertEquals("42", result.path("stdout").asText());
        assertEquals(3, result.path("stdinBytes").asInt());
    }

    /** 验证未提供输入时仍会立即关闭 stdin，而不是让读取程序一直等待。 */
    @Test
    void closesStandardInputWhenNoInputWasProvided() throws Exception {
        Files.writeString(workspace.resolve("ReadEof.java"), """
                class ReadEof {
                    public static void main(String[] args) throws Exception {
                        System.out.print(System.in.read());
                    }
                }
                """);

        JsonNode result = execute("""
                {"command":["java","ReadEof.java"],"timeout_seconds":3}
                """);

        assertFalse(result.path("timedOut").asBoolean(), result::toPrettyString);
        assertEquals("-1", result.path("stdout").asText());
        assertEquals(0, result.path("stdinBytes").asInt());
    }

    /** 验证两种标准输入来源不能同时使用。 */
    @Test
    void rejectsMultipleStandardInputSources() throws Exception {
        ToolExecutionException exception = assertThrows(
                ToolExecutionException.class,
                () -> commandTool.execute(objectMapper.readTree("""
                        {"command":["java","--version"],"stdin":"x","stdin_file":"input.txt"}
                        """))
        );

        assertEquals("INVALID_ARGUMENTS", exception.code());
    }

    /** 验证 Maven 等 Windows .cmd 包装器能够通过受控解释器执行。 */
    @Test
    void executesCommandWrapper() throws Exception {
        JsonNode result = execute("""
                {"command": ["mvn", "--version"]}
                """);

        assertEquals(0, result.path("exitCode").asInt(), result::toPrettyString);
        assertTrue(result.path("stdout").asText().contains("Apache Maven"));
    }

    /** 验证 Windows 项目根目录中的 Maven Wrapper 无需全局安装 Maven。 */
    @Test
    void executesProjectMavenWrapperOnWindows() throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Files.writeString(workspace.resolve("mvnw.cmd"), "@echo project-wrapper\r\n");

        JsonNode result = execute("""
                {"command": ["./mvnw.cmd", "--version"]}
                """);

        assertEquals(0, result.path("exitCode").asInt(), result::toPrettyString);
        assertTrue(result.path("stdout").asText().contains("project-wrapper"));
    }

    /** 验证模型把命令数组额外编码成 JSON 字符串时仍可安全执行。 */
    @Test
    void acceptsJsonEncodedCommandArray() throws Exception {
        var arguments = objectMapper.createObjectNode();
        arguments.put("command", objectMapper.writeValueAsString(List.of("java", "--version")));

        JsonNode result = objectMapper.readTree(commandTool.execute(arguments));

        assertEquals(0, result.path("exitCode").asInt(), result::toPrettyString);
        assertEquals("java", result.path("command").get(0).asText());
        assertEquals("--version", result.path("command").get(1).asText());
    }

    /** 验证普通 Shell 命令字符串不会被自动拆分或绕过数组协议。 */
    @Test
    void rejectsPlainCommandString() throws Exception {
        ToolExecutionException exception = assertThrows(
                ToolExecutionException.class,
                () -> commandTool.execute(objectMapper.readTree("{\"command\":\"java --version\"}"))
        );

        assertEquals("INVALID_ARGUMENTS", exception.code());
        assertEquals("command must be a non-empty string array", exception.getMessage());
    }

    /** 验证可从服务端配置的额外目录发现白名单程序。 */
    @Test
    void resolvesExecutableFromConfiguredSearchPath() throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Path tools = Files.createDirectories(workspace.resolve("tools"));
        Files.writeString(tools.resolve("fake-compiler.cmd"), "@echo configured-tool\r\n");
        CommandProperties commandProperties = new CommandProperties(
                Duration.ofSeconds(3),
                Duration.ofSeconds(5),
                Duration.ofMillis(500),
                64,
                16,
                1024,
                List.of("fake-compiler"),
                List.of(tools)
        );
        commandTool = new ExecuteCommandTool(
                new WorkspacePathResolver(new WorkspaceProperties(workspace, 1024, 1024, 100, 50, 1024, 5)),
                commandProperties,
                objectMapper
        );

        JsonNode result = execute("""
                {"command": ["fake-compiler"]}
                """);

        assertEquals(0, result.path("exitCode").asInt(), result::toPrettyString);
        assertTrue(result.path("stdout").asText().contains("configured-tool"));
    }

    /** 验证工作空间根目录内生成的程序无需逐个加入全局白名单。 */
    @Test
    void executesGeneratedWorkspaceProgram() throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Path whereExecutable = Path.of(System.getenv("SYSTEMROOT"), "System32", "where.exe");
        assumeTrue(Files.isRegularFile(whereExecutable));
        Files.copy(whereExecutable, workspace.resolve("verify.exe"));

        JsonNode result = execute("""
                {"command": ["./verify.exe", "java"]}
                """);

        assertEquals(0, result.path("exitCode").asInt(), result::toPrettyString);
        assertFalse(result.path("stdout").asText().isBlank());
    }

    /** 验证程序失败以非零退出码返回，而不是转换为工具异常。 */
    @Test
    void returnsNonZeroExitCode() throws Exception {
        JsonNode result = execute("""
                {"command": ["java", "DefinitelyMissingSource.java"]}
                """);

        assertNotEquals(0, result.path("exitCode").asInt());
        assertFalse(result.path("timedOut").asBoolean());
        assertFalse(result.path("stderr").asText().isBlank());
    }

    /** 验证持续排空大输出，但只向模型返回配置允许的字符数。 */
    @Test
    void truncatesLargeOutput() throws Exception {
        Files.writeString(workspace.resolve("PrintMany.java"), """
                class PrintMany {
                    public static void main(String[] args) {
                        System.out.print("x".repeat(500));
                    }
                }
                """);

        JsonNode result = execute("""
                {"command": ["java", "PrintMany.java"]}
                """);

        assertEquals(0, result.path("exitCode").asInt());
        assertEquals(64, result.path("stdout").asText().length());
        assertTrue(result.path("stdoutTruncated").asBoolean());
    }

    /** 验证超时命令会被终止并返回 timedOut 状态。 */
    @Test
    void terminatesTimedOutCommand() throws Exception {
        Files.writeString(workspace.resolve("SleepLong.java"), """
                class SleepLong {
                    public static void main(String[] args) throws Exception {
                        Thread.sleep(10_000);
                    }
                }
                """);

        long startedAt = System.nanoTime();
        JsonNode result = execute("""
                {"command": ["java", "SleepLong.java"], "timeout_seconds": 1}
                """);

        assertTrue(result.path("timedOut").asBoolean());
        assertTrue(result.path("exitCode").isNull());
        assertTrue(Duration.ofNanos(System.nanoTime() - startedAt).toSeconds() < 5);
    }

    /** 验证未进入白名单的 Shell 无法执行。 */
    @Test
    void rejectsDisallowedExecutable() throws Exception {
        ToolExecutionException exception = assertThrows(
                ToolExecutionException.class,
                () -> commandTool.execute(objectMapper.readTree("""
                        {"command": ["cmd", "/c", "echo unsafe"]}
                        """))
        );

        assertEquals("COMMAND_NOT_ALLOWED", exception.code());
    }

    /** 验证普通项目批处理脚本仍不能借 Wrapper 例外执行。 */
    @Test
    void rejectsArbitraryWorkspaceBatchScript() throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Files.writeString(workspace.resolve("run.cmd"), "@echo unsafe\r\n");

        ToolExecutionException exception = assertThrows(
                ToolExecutionException.class,
                () -> commandTool.execute(objectMapper.readTree("""
                        {"command": ["./run.cmd"]}
                        """))
        );

        assertEquals("COMMAND_NOT_ALLOWED", exception.code());
    }

    /** 验证解释器不能通过参数直接执行内联代码。 */
    @Test
    void rejectsInlineInterpreterCode() throws Exception {
        ToolExecutionException exception = assertThrows(
                ToolExecutionException.class,
                () -> commandTool.execute(objectMapper.readTree("""
                        {"command": ["python", "-c", "print('unsafe')"]}
                        """))
        );

        assertEquals("COMMAND_NOT_ALLOWED", exception.code());
    }

    /** 验证命令参数不能直接引用工作区外绝对路径。 */
    @Test
    void rejectsAbsolutePathArgument() throws Exception {
        ToolExecutionException exception = assertThrows(
                ToolExecutionException.class,
                () -> commandTool.execute(objectMapper.readTree("""
                        {"command": ["java", "C:\\\\outside\\\\Main.java"]}
                        """))
        );

        assertEquals("PATH_OUTSIDE_WORKSPACE", exception.code());
    }

    /** 验证 Git 仅开放检查类子命令。 */
    @Test
    void rejectsMutatingGitSubcommand() throws Exception {
        ToolExecutionException exception = assertThrows(
                ToolExecutionException.class,
                () -> commandTool.execute(objectMapper.readTree("""
                        {"command": ["git", "reset", "--hard"]}
                        """))
        );

        assertEquals("COMMAND_NOT_ALLOWED", exception.code());
    }

    /** 验证 Windows 批处理包装器不能接收命令拼接元字符。 */
    @Test
    void rejectsWindowsShellMetacharacters() throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        ToolExecutionException exception = assertThrows(
                ToolExecutionException.class,
                () -> commandTool.execute(objectMapper.readTree("""
                        {"command": ["mvn", "--version", "&", "echo unsafe"]}
                        """))
        );

        assertEquals("INVALID_ARGUMENTS", exception.code());
    }

    /** 验证命令工作目录不能逃逸到工作区之外。 */
    @Test
    void rejectsWorkingDirectoryOutsideWorkspace() throws Exception {
        ToolExecutionException exception = assertThrows(
                ToolExecutionException.class,
                () -> commandTool.execute(objectMapper.readTree("""
                        {"command": ["java", "--version"], "working_directory": ".."}
                        """))
        );

        assertEquals("PATH_OUTSIDE_WORKSPACE", exception.code());
    }

    /** 执行 JSON 请求并解析工具结果。 */
    private JsonNode execute(String arguments) throws Exception {
        return objectMapper.readTree(commandTool.execute(objectMapper.readTree(arguments)));
    }
}
