package com.yukina.codingagent.tool.workspace;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "agent.workspace")
public record WorkspaceProperties(
        Path root,
        long maxReadBytes,
        long maxWriteBytes,
        int maxListEntries,
        int maxSearchResults,
        long maxSearchFileBytes,
        int maxDepth
) {

    public WorkspaceProperties {
        if (root == null) {
            throw new IllegalArgumentException("agent.workspace.root must be configured");
        }
        if (maxReadBytes <= 0 || maxWriteBytes <= 0 || maxSearchFileBytes <= 0) {
            throw new IllegalArgumentException("workspace byte limits must be positive");
        }
        if (maxListEntries <= 0 || maxSearchResults <= 0 || maxDepth <= 0) {
            throw new IllegalArgumentException("workspace result limits must be positive");
        }
    }
}
