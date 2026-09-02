package com.yukina.codingagent.deepseek;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * DeepSeek 服务连接配置。
 *
 * @param baseUrl API 基础地址
 * @param apiKey API 密钥
 * @param model 默认模型名称
 * @param requestTimeout 单次请求超时
 * @param thinkingEnabled 是否启用思考模式
 * @param maxRetries 瞬时错误的最大重试次数
 * @param retryDelay 首次重试等待时间
 */
@ConfigurationProperties(prefix = "deepseek")
public record DeepSeekProperties(
        String baseUrl,
        String apiKey,
        String model,
        Duration requestTimeout,
        boolean thinkingEnabled,
        int maxRetries,
        Duration retryDelay
) {

    /**
     * 规范化地址并校验必要配置。
     *
     * @throws IllegalArgumentException 地址、模型、超时或重试配置不合法时抛出
     */
    public DeepSeekProperties {
        baseUrl = stripTrailingSlash(requireText(baseUrl, "deepseek.base-url"));
        model = requireText(model, "deepseek.model");
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(120) : requestTimeout;
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("deepseek.request-timeout must be positive");
        }
        if (maxRetries < 0 || maxRetries > 5) {
            throw new IllegalArgumentException("deepseek.max-retries must be between 0 and 5");
        }
        retryDelay = retryDelay == null ? Duration.ofMillis(500) : retryDelay;
        if (retryDelay.isZero() || retryDelay.isNegative()) {
            throw new IllegalArgumentException("deepseek.retry-delay must be positive");
        }
    }

    /**
     * 构造 DeepSeek Chat Completions 接口地址。
     *
     * @return 由基础地址拼接得到的 Chat Completions URI
     */
    public URI chatCompletionsUri() {
        return URI.create(baseUrl + "/chat/completions");
    }

    /**
     * 返回 API 密钥，并在缺失时给出明确配置错误。
     *
     * @return 已配置的 API 密钥
     * @throws DeepSeekConfigurationException 密钥为空时抛出
     */
    public String requiredApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new DeepSeekConfigurationException(
                    "DEEPSEEK_API_KEY is not configured. Set it as an environment variable before calling DeepSeek."
            );
        }
        return apiKey;
    }

    /**
     * 校验必填文本配置。
     *
     * @param value 配置值
     * @param propertyName 用于错误消息的属性名
     * @return 原配置值
     * @throws IllegalArgumentException 配置为空白时抛出
     */
    private static String requireText(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(propertyName + " must not be blank");
        }
        return value;
    }

    /**
     * 去除基础地址末尾的斜杠，避免拼接出重复分隔符。
     *
     * @param value 已校验基础地址
     * @return 不含末尾斜杠的地址
     */
    private static String stripTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
