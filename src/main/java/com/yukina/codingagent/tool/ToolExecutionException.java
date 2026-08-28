package com.yukina.codingagent.tool;

/**
 * 工具主动抛出的可预期业务异常，携带稳定错误码。
 */
public class ToolExecutionException extends RuntimeException {

    private final String code;

    /** 创建可返回给模型的工具异常。 */
    public ToolExecutionException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** 返回机器可读错误码。 */
    public String code() {
        return code;
    }
}
