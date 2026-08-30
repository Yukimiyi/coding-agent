package com.yukina.codingagent.workspace.exception;

/** 表示指定工作空间不存在。 */
public class WorkspaceNotFoundException extends RuntimeException {

    /** 使用工作空间 ID 创建异常。 */
    public WorkspaceNotFoundException(String workspaceId) {
        super("Workspace not found: " + workspaceId);
    }
}
