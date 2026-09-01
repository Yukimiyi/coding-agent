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
     *
     * @param path 已存在目标文件路径，其父目录用于放置临时文件
     * @param content 要写入的 UTF-8 文本
     * @throws IOException 临时文件创建、写入、移动或清理失败时抛出
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
