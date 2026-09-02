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

    /** DeepSeek 地址、模型、密钥、超时和重试配置。 */
    private final DeepSeekProperties properties;
    /** 请求和响应协议 JSON 映射器。 */
    private final ObjectMapper objectMapper;
    /** 复用连接并执行同步 HTTP 与 SSE 请求的 JDK 客户端。 */
    private final HttpClient httpClient;

    /**
     * 创建 DeepSeek 客户端并按配置初始化连接超时。
     *
     * @param properties DeepSeek 地址、模型、密钥和重试配置
     * @param objectMapper 请求与响应 JSON 转换器
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
     *
     * @param messages 完整对话消息
     * @return DeepSeek 非流式响应
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
     * @throws IllegalArgumentException 消息为空时抛出
     * @throws DeepSeekApiException 配置、网络、HTTP 或响应解析失败时抛出
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
     * @throws IllegalArgumentException 消息为空时抛出
     * @throws DeepSeekApiException 配置、网络、HTTP 或流解析失败时抛出
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

    /**
     * 仅对限流和典型瞬时服务错误执行有限重试。
     *
     * @param statusCode HTTP 状态码
     * @param attempt 从零开始的当前尝试序号
     * @return 未超过配置次数且状态可重试时返回 {@code true}
     */
    private boolean shouldRetry(int statusCode, int attempt) {
        return attempt < properties.maxRetries()
                && (statusCode == 429 || statusCode == 500 || statusCode == 502
                || statusCode == 503 || statusCode == 504);
    }

    /**
     * 使用有上限的指数退避等待下一次请求。
     *
     * @param attempt 从零开始的当前尝试序号
     * @throws DeepSeekApiException 等待期间线程被中断时抛出
     */
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

    /**
     * 解析一行标准 SSE 数据并交给响应聚合器。
     *
     * @param line HTTP 行流中的单行文本
     * @param accumulator 当前响应聚合器
     * @param observer 公开回答增量观察器
     * @throws DeepSeekApiException data JSON 无法解析时抛出
     */
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
     *
     * @param request Chat Completions 请求对象
     * @return 请求 JSON 字符串
     * @throws DeepSeekApiException 序列化失败时抛出
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
        /** 流式响应 ID。 */
        private String id;
        /** 服务端实际使用的模型名称。 */
        private String model;
        /** 最后一个候选增量给出的停止原因。 */
        private String finishReason;
        /** 面向用户的公开回答文本缓冲区。 */
        private final StringBuilder content = new StringBuilder();
        /** 仅供协议连续性使用、不向界面公开的推理文本缓冲区。 */
        private final StringBuilder reasoningContent = new StringBuilder();
        /** 按调用索引聚合跨 SSE 数据块的工具调用。 */
        private final Map<Integer, ToolCallAccumulator> toolCalls = new TreeMap<>();
        /** 流尾返回的完整 Token 用量。 */
        private DeepSeekChatResponse.Usage usage;
        /** 是否至少接收到一个有效候选增量。 */
        private boolean receivedChoice;

        /** 创建空的流式响应聚合器。 */
        private StreamAccumulator() {
        }

        /**
         * 合并单个模型增量，并仅推送公开回答文本。
         *
         * @param chunk 单个 SSE 数据块
         * @param observer 公开回答增量观察器
         */
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

        /**
         * 按工具调用索引拼接跨数据块的 ID、名称与参数。
         *
         * @param deltas 当前数据块中的工具调用增量
         */
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

        /**
         * 将已聚合数据转换为现有非流式响应结构。
         *
         * @return 可供 AgentLoop 统一处理的完整响应
         * @throws DeepSeekApiException 数据流未包含任何候选项时抛出
         */
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

        /**
         * 将空缓冲区转换为协议中的 null。
         *
         * @param value 文本聚合缓冲区
         * @return 完整文本；缓冲区为空时返回 {@code null}
         */
        private static String emptyToNull(StringBuilder value) {
            return value.isEmpty() ? null : value.toString();
        }
    }

    /** 聚合单个流式函数工具调用。 */
    private static final class ToolCallAccumulator {
        /** 跨数据块拼接的工具调用 ID。 */
        private final StringBuilder id = new StringBuilder();
        /** 跨数据块拼接的调用类型。 */
        private final StringBuilder type = new StringBuilder();
        /** 跨数据块拼接的函数名称。 */
        private final StringBuilder name = new StringBuilder();
        /** 跨数据块拼接的 JSON 参数文本。 */
        private final StringBuilder arguments = new StringBuilder();

        /** 创建空的函数工具调用聚合器。 */
        private ToolCallAccumulator() {
        }

        /**
         * 合并单个工具调用增量。
         *
         * @param delta 当前工具调用增量
         */
        private void accept(DeepSeekChatChunk.ToolCallDelta delta) {
            append(id, delta.id());
            append(type, delta.type());
            if (delta.function() != null) {
                append(name, delta.function().name());
                append(arguments, delta.function().arguments());
            }
        }

        /**
         * 将所有增量转换为完整函数工具调用。
         *
         * @return 拼接完成的函数工具调用
         */
        private DeepSeekToolCall result() {
            return new DeepSeekToolCall(
                    id.toString(),
                    type.isEmpty() ? "function" : type.toString(),
                    new DeepSeekToolCall.FunctionCall(name.toString(), arguments.toString())
            );
        }

        /**
         * 忽略空片段并拼接有效字符串。
         *
         * @param target 目标字符串缓冲区
         * @param value 可空增量文本
         */
        private static void append(StringBuilder target, String value) {
            if (value != null) {
                target.append(value);
            }
        }
    }
}
