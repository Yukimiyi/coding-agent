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
                List.of("java", "git", "mvn")
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

    /** 验证 Maven 等 Windows .cmd 包装器能够通过受控解释器执行。 */
    @Test
    void executesCommandWrapper() throws Exception {
        JsonNode result = execute("""
                {"command": ["mvn", "--version"]}
                """);

        assertEquals(0, result.path("exitCode").asInt(), result::toPrettyString);
        assertTrue(result.path("stdout").asText().contains("Apache Maven"));
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
