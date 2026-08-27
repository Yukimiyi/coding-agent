package com.yukina.codingagent.deepseek;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "deepseek")
public record DeepSeekProperties(
        String baseUrl,
        String apiKey,
        String model,
        Duration requestTimeout,
        boolean thinkingEnabled
) {

    public DeepSeekProperties {
        baseUrl = stripTrailingSlash(requireText(baseUrl, "deepseek.base-url"));
        model = requireText(model, "deepseek.model");
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(120) : requestTimeout;
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("deepseek.request-timeout must be positive");
        }
    }

    public URI chatCompletionsUri() {
        return URI.create(baseUrl + "/chat/completions");
    }

    public String requiredApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new DeepSeekConfigurationException(
                    "DEEPSEEK_API_KEY is not configured. Set it as an environment variable before calling DeepSeek."
            );
        }
        return apiKey;
    }

    private static String requireText(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(propertyName + " must not be blank");
        }
        return value;
    }

    private static String stripTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
