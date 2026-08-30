package com.yukina.codingagent.workspace.model;

import java.time.Instant;

/**
 * 一个经过校验并注册的本地项目工作空间。
 *
 * @param id 工作空间 ID
 * @param name 展示名称
 * @param rootPath 规范化后的绝对根目录
 * @param createdAt 创建时间
 * @param updatedAt 最近修改时间
 */
public record Workspace(
        String id,
        String name,
        String rootPath,
        Instant createdAt,
        Instant updatedAt
) {
}
