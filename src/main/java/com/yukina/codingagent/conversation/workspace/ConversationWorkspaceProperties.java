package com.yukina.codingagent.conversation.workspace;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 会话项目目录及浏览器上传边界。
 *
 * @param storageRoot 所有会话和上传暂存数据的应用托管根目录
 * @param maxFiles 单次上传允许的最大文件数量
 * @param maxFileBytes 单个文件允许的最大字节数
 * @param maxTotalBytes 单次上传允许的总字节数
 */
@ConfigurationProperties(prefix = "agent.conversation-workspace")
public record ConversationWorkspaceProperties(
        Path storageRoot,
        int maxFiles,
        long maxFileBytes,
        long maxTotalBytes
) {
    /**
     * 校验路径和上传限制。
     */
    public ConversationWorkspaceProperties {
        if (storageRoot == null) {
            throw new IllegalArgumentException("conversation workspace storage root must be configured");
        }
        if (maxFiles <= 0 || maxFileBytes <= 0 || maxTotalBytes <= 0) {
            throw new IllegalArgumentException("conversation workspace limits must be positive");
        }
        if (maxFileBytes > maxTotalBytes) {
            throw new IllegalArgumentException("max-file-bytes must not exceed max-total-bytes");
        }
    }
}
