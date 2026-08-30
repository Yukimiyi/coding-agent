package com.yukina.codingagent.workspace.exception;

/** 表示工作空间注册、修改或删除发生状态冲突。 */
public class WorkspaceConflictException extends RuntimeException {

    /** 使用可直接返回客户端的说明创建异常。 */
    public WorkspaceConflictException(String message) {
        super(message);
    }
}
