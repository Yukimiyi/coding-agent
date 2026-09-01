package com.yukina.codingagent.agent.perception;

import com.yukina.codingagent.agent.plan.PlanningProperties;
import com.yukina.codingagent.tool.command.ExecutionEnvironmentProvider;
import com.yukina.codingagent.tool.workspace.WorkspaceExecutionContext;
import com.yukina.codingagent.tool.workspace.WorkspaceProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证规划前项目感知的目录边界、描述文件和缓存过滤。 */
class ProjectSnapshotProviderTest {

    @TempDir
    Path workspace;

    /** 快照应保留相对路径和构建摘要，并跳过可再生依赖目录。 */
    @Test
    void capturesBoundedProjectContext() throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        Files.createDirectories(workspace.resolve("node_modules/pkg"));
        Files.writeString(workspace.resolve("pom.xml"), "<project><artifactId>demo</artifactId></project>");
        Files.writeString(workspace.resolve("src/Main.java"), "class Main {}");
        Files.writeString(workspace.resolve("node_modules/pkg/index.js"), "generated");
        ExecutionEnvironmentProvider environment = mock(ExecutionEnvironmentProvider.class);
        when(environment.agentSummary()).thenReturn("Available: java, mvn");
        ProjectSnapshotProvider provider = new ProjectSnapshotProvider(
                new WorkspaceExecutionContext(new WorkspaceProperties(
                        workspace,
                        1024,
                        1024,
                        100,
                        100,
                        1024,
                        5
                )),
                environment,
                new PlanningProperties(true, 6, 10000, 3, 100, 4000, "Plan")
        );

        ProjectSnapshot snapshot = provider.capture();

        assertFalse(snapshot.empty());
        assertTrue(snapshot.files().contains("pom.xml"));
        assertTrue(snapshot.files().contains("src/Main.java"));
        assertTrue(snapshot.files().stream().noneMatch(path -> path.contains("node_modules")));
        assertTrue(snapshot.descriptors().get("pom.xml").contains("artifactId"));
        assertTrue(snapshot.environmentSummary().contains("mvn"));
    }
}
