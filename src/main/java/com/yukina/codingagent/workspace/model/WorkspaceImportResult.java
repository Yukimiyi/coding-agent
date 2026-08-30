package com.yukina.codingagent.workspace.model;

import java.util.List;

/**
 * 一次工作空间内容导入的摘要。
 *
 * @param workspaceId 目标工作空间 ID
 * @param importedFiles 成功写入的文件数
 * @param totalBytes 成功写入的总字节数
 * @param paths 写入的工作空间相对路径
 */
public record WorkspaceImportResult(
        String workspaceId,
        int importedFiles,
        long totalBytes,
        List<String> paths
) {

    /** 固化路径列表，避免响应内容在返回后被修改。 */
    public WorkspaceImportResult {
        paths = List.copyOf(paths);
    }
}
