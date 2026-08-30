package com.yukina.codingagent.workspace;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 浏览器导入工作空间内容时的文件数量和字节限制。
 */
@ConfigurationProperties(prefix = "agent.workspace-import")
public record WorkspaceImportProperties(int maxFiles, long maxFileBytes, long maxTotalBytes) {

    /** 校验导入限制均为正数。 */
    public WorkspaceImportProperties {
        if (maxFiles <= 0 || maxFileBytes <= 0 || maxTotalBytes <= 0) {
            throw new IllegalArgumentException("Workspace import limits must be positive");
        }
        if (maxFileBytes > maxTotalBytes) {
            throw new IllegalArgumentException("max-file-bytes must not exceed max-total-bytes");
        }
    }
}
