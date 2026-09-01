package com.yukina.codingagent.conversation.model;

import java.util.List;

/**
 * 一次编程会话文件导入的摘要。
 *
 * @param conversationId 目标会话 ID
 * @param importedFiles 成功写入的文件数
 * @param totalBytes 成功写入的总字节数
 * @param paths 写入的项目相对路径
 */
public record ConversationImportResult(
        String conversationId,
        int importedFiles,
        long totalBytes,
        List<String> paths
) {
    /** 固化路径列表，避免响应返回后被调用方修改。 */
    public ConversationImportResult {
        paths = List.copyOf(paths);
    }
}
