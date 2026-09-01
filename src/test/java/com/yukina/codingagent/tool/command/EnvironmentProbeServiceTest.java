package com.yukina.codingagent.tool.command;

import com.yukina.codingagent.tool.workspace.WorkspaceExecutionContext;
import com.yukina.codingagent.tool.workspace.WorkspaceProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证宿主能力检测和项目 Wrapper 覆盖行为。 */
class EnvironmentProbeServiceTest {

    @TempDir
    Path workspace;

    /** 项目 Wrapper 应覆盖主机 Maven 状态，且摘要使用精确相对命令。 */
    @Test
    void prefersProjectWrapperAndIncludesItInAgentSummary() throws Exception {
        String wrapperName = isWindows() ? "mvnw.cmd" : "mvnw";
        Files.writeString(workspace.resolve(wrapperName), isWindows() ? "@echo wrapper\r\n" : "#!/bin/sh\n");
        EnvironmentProbeService service = service();

        EnvironmentSnapshot snapshot = service.inspect(workspace, false);
        EnvironmentToolStatus maven = snapshot.tools().stream()
                .filter(tool -> "maven".equals(tool.id()))
                .findFirst()
                .orElseThrow();

        assertTrue(maven.available());
        assertEquals("./" + wrapperName, maven.command());
        assertEquals(EnvironmentToolStatus.Source.PROJECT_WRAPPER, maven.source());
        assertTrue(service.agentSummary().contains("./" + wrapperName));
    }

    /** 未进入命令白名单的工具应明确标记为策略禁用。 */
    @Test
    void marksToolsOutsideAllowlistAsUnavailable() {
        EnvironmentSnapshot snapshot = service().inspect(null, false);
        EnvironmentToolStatus node = snapshot.tools().stream()
                .filter(tool -> "node".equals(tool.id()))
                .findFirst()
                .orElseThrow();

        assertTrue(!node.available());
        assertEquals(EnvironmentToolStatus.Source.UNAVAILABLE, node.source());
        assertTrue(node.message().contains("白名单"));
    }

    /** 仅存在启动器但版本命令失败时，不应向 Agent 宣告对应 SDK 可用。 */
    @Test
    void marksExecutableWithFailingVersionCheckAsUnavailable() throws Exception {
        Path tools = Files.createDirectories(workspace.resolve("tools"));
        String fileName = isWindows() ? "dotnet.cmd" : "dotnet";
        Path executable = tools.resolve(fileName);
        Files.writeString(
                executable,
                isWindows() ? "@echo sdk-missing\r\n@exit /b 1\r\n" : "#!/bin/sh\necho sdk-missing\nexit 1\n"
        );
        if (!isWindows()) {
            Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        CommandProperties properties = new CommandProperties(
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofMillis(200),
                1024, 16, 1024, List.of("dotnet"), List.of(tools)
        );
        EnvironmentProbeService service = new EnvironmentProbeService(
                properties,
                new WorkspaceExecutionContext(new WorkspaceProperties(workspace, 1024, 1024, 100, 50, 1024, 5))
        );

        EnvironmentToolStatus dotnet = service.inspect(null, false).tools().stream()
                .filter(tool -> "dotnet".equals(tool.id()))
                .findFirst()
                .orElseThrow();

        assertTrue(!dotnet.available());
        assertTrue(dotnet.message().contains("版本检测失败"));
        assertTrue(dotnet.message().contains("sdk-missing"));
    }

    /** 创建仅开放 Maven Wrapper 的确定性测试服务。 */
    private EnvironmentProbeService service() {
        CommandProperties commandProperties = new CommandProperties(
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                Duration.ofMillis(200),
                1024,
                16,
                1024,
                List.of("mvnw"),
                List.of()
        );
        WorkspaceProperties workspaceProperties = new WorkspaceProperties(
                workspace, 1024, 1024, 100, 50, 1024, 5
        );
        return new EnvironmentProbeService(
                commandProperties,
                new WorkspaceExecutionContext(workspaceProperties)
        );
    }

    /** 判断测试是否运行于 Windows。 */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
