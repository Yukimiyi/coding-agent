package com.yukina.codingagent.workspace;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 托管工作空间的存储目录与数量限制。
 */
@ConfigurationProperties(prefix = "agent.workspace-registry")
public record WorkspaceRegistryProperties(int maxWorkspaces, Path storageRoot) {

    /** 校验工作空间注册表配置。 */
    public WorkspaceRegistryProperties {
        if (maxWorkspaces <= 0) {
            throw new IllegalArgumentException("agent.workspace-registry.max-workspaces must be positive");
        }
        if (storageRoot == null) {
            throw new IllegalArgumentException("agent.workspace-registry.storage-root must be configured");
        }
    }
}
