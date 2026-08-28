package com.yukina.codingagent.deepseek;

/**
 * 表示 DeepSeek HTTP 调用、序列化或响应解析失败。
 */
public class DeepSeekApiException extends RuntimeException {

    private final int statusCode;

    /**
     * 根据非成功 HTTP 响应创建异常。
     */
    public DeepSeekApiException(int statusCode, String responseBody) {
        super("DeepSeek API returned HTTP " + statusCode + ": " + responseBody);
        this.statusCode = statusCode;
    }

    /**
     * 根据客户端内部错误创建异常。
     */
    public DeepSeekApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
    }

    /**
     * 返回 HTTP 状态码；非 HTTP 错误返回 {@code -1}。
     */
    public int getStatusCode() {
        return statusCode;
    }
}
