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
 */
@ConfigurationProperties(prefix = "deepseek")
public record DeepSeekProperties(
        String baseUrl,
        String apiKey,
        String model,
        Duration requestTimeout,
        boolean thinkingEnabled
) {

    /** 规范化地址并校验必要配置。 */
    public DeepSeekProperties {
        baseUrl = stripTrailingSlash(requireText(baseUrl, "deepseek.base-url"));
        model = requireText(model, "deepseek.model");
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(120) : requestTimeout;
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("deepseek.request-timeout must be positive");
        }
    }

    /** 返回 Chat Completions 接口地址。 */
    public URI chatCompletionsUri() {
        return URI.create(baseUrl + "/chat/completions");
    }

    /** 返回 API 密钥，并在缺失时给出明确配置错误。 */
    public String requiredApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new DeepSeekConfigurationException(
                    "DEEPSEEK_API_KEY is not configured. Set it as an environment variable before calling DeepSeek."
            );
        }
        return apiKey;
    }

    /** 校验必填文本配置。 */
    private static String requireText(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(propertyName + " must not be blank");
        }
        return value;
    }

    /** 去除基础地址末尾的斜杠，避免拼接出重复分隔符。 */
    private static String stripTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
