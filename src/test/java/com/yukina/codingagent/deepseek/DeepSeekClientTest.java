package com.yukina.codingagent.deepseek;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 DeepSeek HTTP 协议、工具调用消息和异常映射。 */
class DeepSeekClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    /** 在每个用例后关闭本地 HTTP 测试服务器。 */
    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 验证普通对话请求符合 OpenAI 兼容格式。 */
    @Test
    void sendsOpenAiCompatibleChatRequest() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {
                      "id": "chat-1",
                      "model": "deepseek-v4-flash",
                      "choices": [
                        {
                          "index": 0,
                          "message": {"role": "assistant", "content": "Hello from DeepSeek"},
                          "finish_reason": "stop"
                        }
                      ],
                      "usage": {
                        "prompt_tokens": 5,
                        "completion_tokens": 4,
                        "total_tokens": 9
                      }
                    }
                    """);
        });

        DeepSeekClient client = createClient("test-api-key");
        DeepSeekChatResponse response = client.chat(List.of(
                DeepSeekMessage.user("Hello")
        ));

        assertEquals("Bearer test-api-key", authorization.get());
        assertEquals("Hello from DeepSeek", response.firstContent());
        assertEquals(9, response.usage().totalTokens());

        JsonNode json = objectMapper.readTree(requestBody.get());
        assertEquals("deepseek-v4-flash", json.path("model").asText());
        assertEquals("disabled", json.path("thinking").path("type").asText());
        assertEquals(false, json.path("stream").asBoolean());
        assertFalse(json.has("tools"));
        assertEquals("user", json.path("messages").get(0).path("role").asText());
        assertEquals("Hello", json.path("messages").get(0).path("content").asText());
    }

    /** 验证工具定义发送和工具调用响应解析。 */
    @Test
    void sendsToolDefinitionsAndParsesToolCalls() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {
                      "id": "chat-tool-1",
                      "model": "deepseek-v4-flash",
                      "choices": [
                        {
                          "index": 0,
                          "message": {
                            "role": "assistant",
                            "content": null,
                            "reasoning_content": "I need to inspect the file.",
                            "tool_calls": [
                              {
                                "id": "call_read_1",
                                "type": "function",
                                "function": {
                                  "name": "read_file",
                                  "arguments": "{\\\"path\\\":\\\"pom.xml\\\"}"
                                }
                              }
                            ]
                          },
                          "finish_reason": "tool_calls"
                        }
                      ]
                    }
                    """);
        });

        DeepSeekChatResponse response = createClient("test-api-key").chat(
                List.of(DeepSeekMessage.user("Read pom.xml")),
                List.of(readFileTool())
        );

        DeepSeekMessage message = response.firstMessage();
        assertEquals("I need to inspect the file.", message.reasoningContent());
        assertEquals("call_read_1", message.toolCalls().getFirst().id());
        assertEquals("read_file", message.toolCalls().getFirst().function().name());
        assertEquals("{\"path\":\"pom.xml\"}", message.toolCalls().getFirst().function().arguments());
        assertEquals("tool_calls", response.choices().getFirst().finishReason());

        JsonNode json = objectMapper.readTree(requestBody.get());
        JsonNode function = json.path("tools").get(0).path("function");
        assertEquals("function", json.path("tools").get(0).path("type").asText());
        assertEquals("read_file", function.path("name").asText());
        assertEquals("object", function.path("parameters").path("type").asText());
        assertEquals("string", function.path("parameters").path("properties").path("path").path("type").asText());
    }

    /** 验证助手工具调用及工具结果可在下一轮完整重放。 */
    @Test
    void replaysAssistantToolCallAndToolResultMessages() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {
                      "id": "chat-tool-2",
                      "model": "deepseek-v4-flash",
                      "choices": [
                        {
                          "index": 0,
                          "message": {"role": "assistant", "content": "The project uses Maven."},
                          "finish_reason": "stop"
                        }
                      ]
                    }
                    """);
        });

        DeepSeekToolCall toolCall = new DeepSeekToolCall(
                "call_read_1",
                "function",
                new DeepSeekToolCall.FunctionCall("read_file", "{\"path\":\"pom.xml\"}")
        );
        List<DeepSeekMessage> messages = List.of(
                DeepSeekMessage.user("Inspect this project"),
                DeepSeekMessage.assistant(null, "I should read pom.xml.", List.of(toolCall)),
                DeepSeekMessage.tool("call_read_1", "<project>...</project>")
        );

        DeepSeekChatResponse response = createClient("test-api-key").chat(
                messages,
                List.of(readFileTool())
        );

        assertEquals("The project uses Maven.", response.firstContent());
        JsonNode json = objectMapper.readTree(requestBody.get());
        JsonNode assistant = json.path("messages").get(1);
        JsonNode tool = json.path("messages").get(2);
        assertEquals("I should read pom.xml.", assistant.path("reasoning_content").asText());
        assertEquals("call_read_1", assistant.path("tool_calls").get(0).path("id").asText());
        assertFalse(assistant.has("tool_call_id"));
        assertEquals("tool", tool.path("role").asText());
        assertEquals("call_read_1", tool.path("tool_call_id").asText());
        assertEquals("<project>...</project>", tool.path("content").asText());
    }

    /** 验证公开回答文本会按 SSE 数据块实时回调并聚合为完整响应。 */
    @Test
    void streamsPublicAnswerDeltasAndUsage() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> accept = new AtomicReference<>();
        startServer(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            respondStream(exchange, List.of(
                    "{\"id\":\"stream-1\",\"model\":\"deepseek-v4-flash\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Hel\"}}]}",
                    "{\"id\":\"stream-1\",\"model\":\"deepseek-v4-flash\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lo\"},\"finish_reason\":\"stop\"}]}",
                    "{\"id\":\"stream-1\",\"model\":\"deepseek-v4-flash\",\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}"
            ));
        });
        List<String> deltas = new ArrayList<>();

        DeepSeekChatResponse response = createClient("test-api-key").chatStream(
                List.of(DeepSeekMessage.user("Hello")),
                List.of(),
                deltas::add
        );

        assertEquals("text/event-stream", accept.get());
        assertEquals(List.of("Hel", "lo"), deltas);
        assertEquals("Hello", response.firstContent());
        assertEquals(7, response.usage().totalTokens());
        JsonNode json = objectMapper.readTree(requestBody.get());
        assertTrue(json.path("stream").asBoolean());
        assertTrue(json.path("stream_options").path("include_usage").asBoolean());
    }

    /** 验证流式工具调用的名称、参数与内部协议推理内容可跨块拼接。 */
    @Test
    void aggregatesStreamedToolCallFragments() throws Exception {
        startServer(exchange -> respondStream(exchange, List.of(
                "{\"id\":\"stream-tool\",\"model\":\"deepseek-v4-flash\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"reasoning_content\":\"inspect \",\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"\"}}]}}]}",
                "{\"id\":\"stream-tool\",\"model\":\"deepseek-v4-flash\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"file\",\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"README.md\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}"
        )));
        List<String> publicDeltas = new ArrayList<>();

        DeepSeekChatResponse response = createClient("test-api-key").chatStream(
                List.of(DeepSeekMessage.user("Read the file")),
                List.of(readFileTool()),
                publicDeltas::add
        );

        DeepSeekMessage message = response.firstMessage();
        assertTrue(publicDeltas.isEmpty());
        assertEquals("inspect file", message.reasoningContent());
        assertEquals("call_1", message.toolCalls().getFirst().id());
        assertEquals("read_file", message.toolCalls().getFirst().function().name());
        assertEquals("{\"path\":\"README.md\"}", message.toolCalls().getFirst().function().arguments());
    }

    /** 验证非成功 HTTP 状态码会保留在 API 异常中。 */
    @Test
    void exposesApiErrorStatus() throws Exception {
        startServer(exchange -> respond(exchange, 401, "{\"error\":\"invalid api key\"}"));

        DeepSeekApiException exception = assertThrows(
                DeepSeekApiException.class,
                () -> createClient("bad-key").chat(List.of(
                        DeepSeekMessage.user("Hello")
                ))
        );

        assertEquals(401, exception.getStatusCode());
    }

    /** 验证缺失密钥时会在发送网络请求前失败。 */
    @Test
    void rejectsMissingApiKeyBeforeSendingRequest() {
        DeepSeekConfigurationException exception = assertThrows(
                DeepSeekConfigurationException.class,
                () -> createClient("").chat(List.of(
                        DeepSeekMessage.user("Hello")
                ))
        );

        assertEquals(
                "DEEPSEEK_API_KEY is not configured. Set it as an environment variable before calling DeepSeek.",
                exception.getMessage()
        );
    }

    /** 创建指向本地测试服务器的 DeepSeek 客户端。 */
    private DeepSeekClient createClient(String apiKey) {
        int port = server == null ? 1 : server.getAddress().getPort();
        DeepSeekProperties properties = new DeepSeekProperties(
                "http://localhost:" + port,
                apiKey,
                "deepseek-v4-flash",
                Duration.ofSeconds(5),
                false
        );
        return new DeepSeekClient(properties, objectMapper);
    }

    /** 创建用于协议测试的 read_file 工具定义。 */
    private static DeepSeekToolDefinition readFileTool() {
        return DeepSeekToolDefinition.function(
                "read_file",
                "Read a UTF-8 text file from the current workspace.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of(
                                        "type", "string",
                                        "description", "Workspace-relative file path"
                                )
                        ),
                        "required", List.of("path"),
                        "additionalProperties", false
                )
        );
    }

    /** 启动仅处理 Chat Completions 路径的本地 HTTP 服务。 */
    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    /** 向测试客户端写入 JSON 响应。 */
    private static void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /** 按 DeepSeek SSE 格式写入多个数据块与结束标记。 */
    private static void respondStream(HttpExchange exchange, List<String> chunks) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        for (String chunk : chunks) {
            exchange.getResponseBody().write(("data: " + chunk + "\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
        }
        exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
    }

    /** 表示单个测试 HTTP 请求处理动作。 */
    @FunctionalInterface
    private interface ExchangeHandler {
        /** 处理一次 HTTP 交换。 */
        void handle(HttpExchange exchange) throws IOException;
    }
}
