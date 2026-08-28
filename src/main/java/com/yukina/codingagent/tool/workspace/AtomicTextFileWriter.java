package com.yukina.codingagent.tool.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 通过同目录临时文件原子替换 UTF-8 文本文件。
 */
public final class AtomicTextFileWriter {

    /** 禁止实例化原子文件写入工具类。 */
    private AtomicTextFileWriter() {
    }

    /**
     * 先完整写入临时文件再替换目标，平台不支持原子移动时使用普通替换。
     */
    public static void replace(Path path, String content) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), ".coding-agent-", ".tmp");
        try {
            Files.writeString(
                    temporary,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
