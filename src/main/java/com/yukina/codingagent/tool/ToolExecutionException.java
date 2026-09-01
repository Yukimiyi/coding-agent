package com.yukina.codingagent.tool;

import java.util.Map;

/**
 * 工具主动抛出的可预期业务异常，携带稳定错误码。
 */
public class ToolExecutionException extends RuntimeException {

    private final String code;
    private final Map<String, Object> details;

    /**
     * 创建可返回给模型的工具异常。
     *
     * @param code 稳定机器可读错误码
     * @param message 面向模型和用户的错误说明
     */
    public ToolExecutionException(String code, String message) {
        this(code, message, Map.of());
    }

    /**
     * 创建包含可恢复信息等扩展字段的工具异常。
     *
     * @param code 稳定机器可读错误码
     * @param message 面向模型和用户的错误说明
     * @param details 可安全序列化的恢复信息；为 {@code null} 时使用空映射
     */
    public ToolExecutionException(String code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    /** @return 机器可读错误码 */
    public String code() {
        return code;
    }

    /** @return 不可变扩展错误字段 */
    public Map<String, Object> details() {
        return details;
    }
}
