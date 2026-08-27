package com.yukina.codingagent.tool.workspace;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

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

    private WorkspaceFilePolicy() {
    }

    public static boolean isExcludedDirectory(Path directory) {
        Path fileName = directory.getFileName();
        return fileName != null
                && EXCLUDED_DIRECTORIES.contains(fileName.toString().toLowerCase(Locale.ROOT));
    }
}
