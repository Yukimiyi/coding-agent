package com.yukina.codingagent.tool.workspace;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 工作区文件工具的根目录和资源上限配置。
 *
 * @param root 无运行绑定时使用的默认工作空间根目录
 * @param maxReadBytes 单次读取文件的最大字节数
 * @param maxWriteBytes 单次写入文件的最大字节数
 * @param maxListEntries 文件列表最大条目数
 * @param maxSearchResults 文本搜索最大结果数
 * @param maxSearchFileBytes 搜索时允许扫描的单文件最大字节数
 * @param maxDepth 文件遍历最大深度
 */
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

    /**
     * 校验根目录和各项资源上限。
     *
     * @throws IllegalArgumentException 根目录缺失或任一资源上限非正数时抛出
     */
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
