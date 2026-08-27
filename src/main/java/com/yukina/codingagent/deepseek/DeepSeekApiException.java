package com.yukina.codingagent.deepseek;

public class DeepSeekApiException extends RuntimeException {

    private final int statusCode;

    public DeepSeekApiException(int statusCode, String responseBody) {
        super("DeepSeek API returned HTTP " + statusCode + ": " + responseBody);
        this.statusCode = statusCode;
    }

    public DeepSeekApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
