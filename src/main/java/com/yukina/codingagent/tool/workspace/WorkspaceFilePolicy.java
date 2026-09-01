package com.yukina.codingagent.tool.workspace;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * 定义文件遍历工具需要跳过的构建产物和元数据目录。
 */
public final class WorkspaceFilePolicy {

    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git",
            ".gradle",
            ".idea",
            "build",
            "dist",
            "node_modules",
            "out",
            "target"
    );

    /** 禁止实例化文件策略工具类。 */
    private WorkspaceFilePolicy() {
    }

    /**
     * 判断目录是否应从列表和搜索中排除。
     *
     * @param directory 待检查目录路径
     * @return 目录名属于构建产物或元数据目录集合时返回 {@code true}
     */
    public static boolean isExcludedDirectory(Path directory) {
        Path fileName = directory.getFileName();
        return fileName != null
                && EXCLUDED_DIRECTORIES.contains(fileName.toString().toLowerCase(Locale.ROOT));
    }
}
