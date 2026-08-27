package com.yukina.codingagent.deepseek;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

@Component
public class DeepSeekClient {

    private final DeepSeekProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DeepSeekClient(DeepSeekProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.requestTimeout())
                .build();
    }

    public DeepSeekChatResponse chat(List<DeepSeekMessage> messages) {
        return chat(messages, List.of());
    }

    public DeepSeekChatResponse chat(
            List<DeepSeekMessage> messages,
            List<DeepSeekToolDefinition> tools
    ) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        List<DeepSeekToolDefinition> safeTools = tools == null ? List.of() : List.copyOf(tools);

        DeepSeekChatRequest requestBody = new DeepSeekChatRequest(
                properties.model(),
                List.copyOf(messages),
                safeTools,
                new DeepSeekChatRequest.Thinking(properties.thinkingEnabled() ? "enabled" : "disabled"),
                false
        );

        HttpRequest request = HttpRequest.newBuilder(properties.chatCompletionsUri())
                .timeout(properties.requestTimeout())
                .header("Authorization", "Bearer " + properties.requiredApiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(requestBody)))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new DeepSeekApiException(response.statusCode(), response.body());
            }
            return objectMapper.readValue(response.body(), DeepSeekChatResponse.class);
        } catch (JacksonException exception) {
            throw new DeepSeekApiException("Failed to parse DeepSeek response", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DeepSeekApiException("DeepSeek API request was interrupted", exception);
        } catch (IOException exception) {
            throw new DeepSeekApiException("Failed to call DeepSeek API", exception);
        }
    }

    private String toJson(DeepSeekChatRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JacksonException exception) {
            throw new DeepSeekApiException("Failed to serialize DeepSeek request", exception);
        }
    }
}
