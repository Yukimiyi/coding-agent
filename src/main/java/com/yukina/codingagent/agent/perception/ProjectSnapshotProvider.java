package com.yukina.codingagent.agent.perception;

import com.yukina.codingagent.agent.plan.PlanningProperties;
import com.yukina.codingagent.tool.command.ExecutionEnvironmentProvider;
import com.yukina.codingagent.tool.workspace.WorkspaceExecutionContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 在当前会话工作目录中生成轻量项目快照，供 Planner 在无工具条件下制定计划。
 */
@Component
public class ProjectSnapshotProvider {

    /** 不参与感知的依赖、构建产物和版本控制目录。 */
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
            ".git", ".gradle", ".idea", "node_modules", "target", "out", "build", "dist"
    );
    /** 允许读取少量内容、用于识别项目类型的描述文件名。 */
    private static final Set<String> DESCRIPTOR_NAMES = Set.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "package.json", "readme.md", "readme.txt", "gradle.properties", "cargo.toml", "go.mod"
    );

    /** 提供当前运行绑定的会话工作目录。 */
    private final WorkspaceExecutionContext workspaceExecutionContext;
    /** 提供不暴露宿主机路径的执行环境摘要。 */
    private final ExecutionEnvironmentProvider executionEnvironmentProvider;
    /** 控制目录深度、文件数量和描述文本预算。 */
    private final PlanningProperties properties;

    /**
     * 创建项目快照提供者。
     *
     * @param workspaceExecutionContext 当前 CODE 会话根目录
     * @param executionEnvironmentProvider 执行环境摘要提供者
     * @param properties 感知深度、条目和文本上限
     */
    public ProjectSnapshotProvider(
            WorkspaceExecutionContext workspaceExecutionContext,
            ExecutionEnvironmentProvider executionEnvironmentProvider,
            PlanningProperties properties
    ) {
        this.workspaceExecutionContext = workspaceExecutionContext;
        this.executionEnvironmentProvider = executionEnvironmentProvider;
        this.properties = properties;
    }

    /**
     * 采集当前工作区的相对路径及少量关键描述文件。
     *
     * @return 不包含绝对路径的受限项目快照
     * @throws IllegalStateException 工作区无法遍历时抛出
     */
    public ProjectSnapshot capture() {
        Path root = workspaceExecutionContext.root();
        List<Path> entries;
        try (Stream<Path> stream = Files.walk(root, properties.snapshotDepth())) {
            entries = stream
                    .filter(path -> !path.equals(root))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> !isInsideSkippedDirectory(root, path))
                    .sorted(Comparator.comparing(path -> display(root, path)))
                    .limit((long) properties.maxSnapshotFiles() + 1)
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to capture project snapshot", exception);
        }

        boolean truncated = entries.size() > properties.maxSnapshotFiles();
        List<Path> retained = truncated ? entries.subList(0, properties.maxSnapshotFiles()) : entries;
        List<String> files = retained.stream().map(path -> display(root, path)).toList();
        Map<String, String> descriptors = new LinkedHashMap<>();
        int remainingDescriptorChars = properties.maxDescriptorChars();
        for (Path path : retained) {
            if (remainingDescriptorChars <= 0 || !Files.isRegularFile(path) || !isDescriptor(path)) {
                continue;
            }
            String content = readText(path, remainingDescriptorChars);
            if (content == null) {
                continue;
            }
            descriptors.put(display(root, path), content);
            remainingDescriptorChars -= content.length();
            try {
                if (Files.size(path) > content.getBytes(StandardCharsets.UTF_8).length) {
                    truncated = true;
                }
            } catch (IOException exception) {
                truncated = true;
            }
        }
        return new ProjectSnapshot(
                files.stream().noneMatch(path -> !path.endsWith("/")),
                files,
                descriptors,
                executionEnvironmentProvider.agentSummary(),
                truncated
        );
    }

    /**
     * 判断路径是否位于应忽略的可再生目录内。
     *
     * @param root 工作区根目录
     * @param path 待检查路径
     * @return 路径任一相对层级命中忽略目录时返回 {@code true}
     */
    private static boolean isInsideSkippedDirectory(Path root, Path path) {
        Path relative = root.relativize(path);
        for (Path part : relative) {
            if (SKIPPED_DIRECTORIES.contains(part.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断文件名是否属于 Planner 有价值的项目描述文件。
     *
     * @param path 待检查文件
     * @return 文件名命中描述文件白名单时返回 {@code true}
     */
    private static boolean isDescriptor(Path path) {
        Path fileName = path.getFileName();
        return fileName != null && DESCRIPTOR_NAMES.contains(fileName.toString().toLowerCase(Locale.ROOT));
    }

    /**
     * 读取至多给定字符数的 UTF-8 文本；非法文本或读取失败时跳过。
     *
     * @param path 描述文件
     * @param maxChars 剩余字符预算
     * @return 文本摘要；不可读取时为 {@code null}
     */
    private static String readText(Path path, int maxChars) {
        int maxBytes = Math.max(1, Math.min(maxChars * 4, 65536));
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(maxBytes);
            String content = new String(bytes, StandardCharsets.UTF_8);
            return content.substring(0, Math.min(content.length(), maxChars));
        } catch (IOException exception) {
            return null;
        }
    }

    /**
     * 生成适合模型读取、且不暴露绝对目录的相对路径。
     *
     * @param root 工作区根目录
     * @param path 工作区内文件或目录
     * @return 使用正斜杠且为目录添加尾斜杠的相对路径
     */
    private static String display(Path root, Path path) {
        String relative = root.relativize(path).toString().replace('\\', '/');
        return Files.isDirectory(path) ? relative + "/" : relative;
    }
}
