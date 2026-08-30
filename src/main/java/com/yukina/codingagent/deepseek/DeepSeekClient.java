package com.yukina.codingagent.deepseek;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 使用 JDK HttpClient 调用 DeepSeek Chat Completions API。
 */
@Component
public class DeepSeekClient {

    private final DeepSeekProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /**
     * 创建 DeepSeek 客户端并按配置初始化连接超时。
     */
    public DeepSeekClient(DeepSeekProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.requestTimeout())
                .build();
    }

    /**
     * 发起不携带工具定义的普通对话请求。
     */
    public DeepSeekChatResponse chat(List<DeepSeekMessage> messages) {
        return chat(messages, List.of());
    }

    /**
     * 发起可携带工具定义的对话请求。
     *
     * @param messages 完整对话消息
     * @param tools 可供模型调用的工具定义
     * @return DeepSeek 响应
     */
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
                false,
                null
        );

        HttpRequest request = HttpRequest.newBuilder(properties.chatCompletionsUri())
                .timeout(properties.requestTimeout())
                .header("Authorization", "Bearer " + properties.requiredApiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(requestBody)))
                .build();

        for (int attempt = 0; ; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    if (shouldRetry(response.statusCode(), attempt)) {
                        awaitRetry(attempt);
                        continue;
                    }
                    throw new DeepSeekApiException(response.statusCode(), response.body());
                }
                return objectMapper.readValue(response.body(), DeepSeekChatResponse.class);
            } catch (JacksonException exception) {
                throw new DeepSeekApiException("Failed to parse DeepSeek response", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new DeepSeekApiException("DeepSeek API request was interrupted", exception);
            } catch (IOException exception) {
                if (attempt < properties.maxRetries()) {
                    awaitRetry(attempt);
                    continue;
                }
                throw new DeepSeekApiException("Failed to call DeepSeek API", exception);
            }
        }
    }

    /**
     * 发起流式对话请求，并在收到公开回答片段时立即通知观察器。
     * 原始推理内容仅聚合进协议消息，不会通过观察器向界面公开。
     *
     * @param messages 完整对话消息
     * @param tools 可供模型调用的工具定义
     * @param observer 公开回答增量观察器
     * @return 聚合后的完整 DeepSeek 响应
     */
    public DeepSeekChatResponse chatStream(
            List<DeepSeekMessage> messages,
            List<DeepSeekToolDefinition> tools,
            DeepSeekStreamObserver observer
    ) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        List<DeepSeekToolDefinition> safeTools = tools == null ? List.of() : List.copyOf(tools);
        DeepSeekStreamObserver safeObserver = observer == null ? DeepSeekStreamObserver.NONE : observer;
        DeepSeekChatRequest requestBody = new DeepSeekChatRequest(
                properties.model(),
                List.copyOf(messages),
                safeTools,
                new DeepSeekChatRequest.Thinking(properties.thinkingEnabled() ? "enabled" : "disabled"),
                true,
                new DeepSeekChatRequest.StreamOptions(true)
        );

        HttpRequest request = HttpRequest.newBuilder(properties.chatCompletionsUri())
                .timeout(properties.requestTimeout())
                .header("Authorization", "Bearer " + properties.requiredApiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(requestBody)))
                .build();

        for (int attempt = 0; ; attempt++) {
            try {
                HttpResponse<Stream<String>> response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofLines()
                );
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    try (Stream<String> lines = response.body()) {
                        String body = String.join("\n", lines.toList());
                        if (shouldRetry(response.statusCode(), attempt)) {
                            awaitRetry(attempt);
                            continue;
                        }
                        throw new DeepSeekApiException(response.statusCode(), body);
                    }
                }
                StreamAccumulator accumulator = new StreamAccumulator();
                try (Stream<String> lines = response.body()) {
                    lines.forEach(line -> acceptStreamLine(line, accumulator, safeObserver));
                }
                return accumulator.result();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new DeepSeekApiException("DeepSeek API request was interrupted", exception);
            } catch (IOException exception) {
                if (attempt < properties.maxRetries()) {
                    awaitRetry(attempt);
                    continue;
                }
                throw new DeepSeekApiException("Failed to call DeepSeek API", exception);
            }
        }
    }

    /** 仅对限流和典型瞬时服务错误执行有限重试。 */
    private boolean shouldRetry(int statusCode, int attempt) {
        return attempt < properties.maxRetries()
                && (statusCode == 429 || statusCode == 500 || statusCode == 502
                || statusCode == 503 || statusCode == 504);
    }

    /** 使用有上限的指数退避等待下一次请求。 */
    private void awaitRetry(int attempt) {
        long multiplier = 1L << Math.min(attempt, 4);
        long delayMillis = Math.min(
                properties.retryDelay().toMillis() * multiplier,
                properties.requestTimeout().toMillis()
        );
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DeepSeekApiException("DeepSeek API retry was interrupted", exception);
        }
    }

    /** 解析一行标准 SSE 数据并交给响应聚合器。 */
    private void acceptStreamLine(
            String line,
            StreamAccumulator accumulator,
            DeepSeekStreamObserver observer
    ) {
        if (line == null || line.isBlank() || !line.startsWith("data:")) {
            return;
        }
        String data = line.substring("data:".length()).trim();
        if (data.isEmpty() || "[DONE]".equals(data)) {
            return;
        }
        try {
            accumulator.accept(objectMapper.readValue(data, DeepSeekChatChunk.class), observer);
        } catch (JacksonException exception) {
            throw new DeepSeekApiException("Failed to parse DeepSeek streaming response", exception);
        }
    }

    /**
     * 将请求对象序列化为 DeepSeek 所需的 JSON。
     */
    private String toJson(DeepSeekChatRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JacksonException exception) {
            throw new DeepSeekApiException("Failed to serialize DeepSeek request", exception);
        }
    }

    /** 聚合文本、推理协议字段、工具调用和用量数据。 */
    private static final class StreamAccumulator {
        private String id;
        private String model;
        private String finishReason;
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoningContent = new StringBuilder();
        private final Map<Integer, ToolCallAccumulator> toolCalls = new TreeMap<>();
        private DeepSeekChatResponse.Usage usage;
        private boolean receivedChoice;

        /** 合并单个模型增量，并仅推送公开回答文本。 */
        private void accept(DeepSeekChatChunk chunk, DeepSeekStreamObserver observer) {
            if (chunk == null) {
                return;
            }
            if (chunk.id() != null) {
                id = chunk.id();
            }
            if (chunk.model() != null) {
                model = chunk.model();
            }
            if (chunk.usage() != null) {
                usage = chunk.usage();
            }
            if (chunk.choices() == null) {
                return;
            }
            for (DeepSeekChatChunk.Choice choice : chunk.choices()) {
                if (choice == null) {
                    continue;
                }
                receivedChoice = true;
                if (choice.finishReason() != null) {
                    finishReason = choice.finishReason();
                }
                DeepSeekChatChunk.Delta delta = choice.delta();
                if (delta == null) {
                    continue;
                }
                if (delta.content() != null && !delta.content().isEmpty()) {
                    content.append(delta.content());
                    observer.onContentDelta(delta.content());
                }
                if (delta.reasoningContent() != null) {
                    reasoningContent.append(delta.reasoningContent());
                }
                mergeToolCalls(delta.toolCalls());
            }
        }

        /** 按工具调用索引拼接跨数据块的 ID、名称与参数。 */
        private void mergeToolCalls(List<DeepSeekChatChunk.ToolCallDelta> deltas) {
            if (deltas == null) {
                return;
            }
            for (int position = 0; position < deltas.size(); position++) {
                DeepSeekChatChunk.ToolCallDelta delta = deltas.get(position);
                if (delta == null) {
                    continue;
                }
                int index = delta.index() == null ? position : delta.index();
                toolCalls.computeIfAbsent(index, ignored -> new ToolCallAccumulator()).accept(delta);
            }
        }

        /** 将已聚合数据转换为现有非流式响应结构。 */
        private DeepSeekChatResponse result() {
            if (!receivedChoice) {
                throw new DeepSeekApiException("DeepSeek API returned no choices", null);
            }
            List<DeepSeekToolCall> calls = new ArrayList<>();
            for (ToolCallAccumulator accumulator : toolCalls.values()) {
                calls.add(accumulator.result());
            }
            DeepSeekMessage message = DeepSeekMessage.assistant(
                    emptyToNull(content),
                    emptyToNull(reasoningContent),
                    calls.isEmpty() ? null : calls
            );
            return new DeepSeekChatResponse(
                    id,
                    model,
                    List.of(new DeepSeekChatResponse.Choice(0, message, finishReason)),
                    usage
            );
        }

        /** 将空缓冲区转换为协议中的 null。 */
        private static String emptyToNull(StringBuilder value) {
            return value.isEmpty() ? null : value.toString();
        }
    }

    /** 聚合单个流式函数工具调用。 */
    private static final class ToolCallAccumulator {
        private final StringBuilder id = new StringBuilder();
        private final StringBuilder type = new StringBuilder();
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();

        /** 合并单个工具调用增量。 */
        private void accept(DeepSeekChatChunk.ToolCallDelta delta) {
            append(id, delta.id());
            append(type, delta.type());
            if (delta.function() != null) {
                append(name, delta.function().name());
                append(arguments, delta.function().arguments());
            }
        }

        /** 生成完整工具调用。 */
        private DeepSeekToolCall result() {
            return new DeepSeekToolCall(
                    id.toString(),
                    type.isEmpty() ? "function" : type.toString(),
                    new DeepSeekToolCall.FunctionCall(name.toString(), arguments.toString())
            );
        }

        /** 忽略空片段并拼接有效字符串。 */
        private static void append(StringBuilder target, String value) {
            if (value != null) {
                target.append(value);
            }
        }
    }
}
