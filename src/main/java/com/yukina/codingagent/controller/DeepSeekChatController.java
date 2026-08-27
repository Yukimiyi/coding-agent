package com.yukina.codingagent.controller;

import com.yukina.codingagent.deepseek.DeepSeekChatResponse;
import com.yukina.codingagent.deepseek.DeepSeekClient;
import com.yukina.codingagent.deepseek.DeepSeekMessage;
import com.yukina.codingagent.deepseek.DeepSeekToolCall;
import com.yukina.codingagent.deepseek.DeepSeekToolDefinition;
import com.yukina.codingagent.tool.ToolRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/deepseek")
public class DeepSeekChatController {

    private final DeepSeekClient deepSeekClient;
    private final ToolRegistry toolRegistry;

    public DeepSeekChatController(DeepSeekClient deepSeekClient, ToolRegistry toolRegistry) {
        this.deepSeekClient = deepSeekClient;
        this.toolRegistry = toolRegistry;
    }

    @PostMapping("/chat")
    @ResponseStatus(HttpStatus.OK)
    public ChatResponse chat(@RequestBody ChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message must not be blank");
        }

        DeepSeekChatResponse response = deepSeekClient.chat(List.of(
                DeepSeekMessage.user(request.message())
        ));

        return new ChatResponse(
                response.id(),
                response.model(),
                response.firstContent(),
                response.usage()
        );
    }

    @PostMapping("/tool-call")
    @ResponseStatus(HttpStatus.OK)
    public ToolCallResponse toolCall(@RequestBody ToolCallRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message must not be blank");
        }
        List<DeepSeekToolDefinition> tools = request.tools() == null || request.tools().isEmpty()
                ? toolRegistry.definitions()
                : request.tools();

        DeepSeekChatResponse response = deepSeekClient.chat(
                List.of(DeepSeekMessage.user(request.message())),
                tools
        );
        DeepSeekMessage message = response.firstMessage();

        return new ToolCallResponse(
                response.id(),
                response.model(),
                message.content(),
                message.reasoningContent(),
                message.toolCalls(),
                response.choices().getFirst().finishReason()
        );
    }

    public record ChatRequest(String message) {
    }

    public record ChatResponse(
            String id,
            String model,
            String content,
            DeepSeekChatResponse.Usage usage
    ) {
    }

    public record ToolCallRequest(
            String message,
            List<DeepSeekToolDefinition> tools
    ) {
    }

    public record ToolCallResponse(
            String id,
            String model,
            String content,
            String reasoningContent,
            List<DeepSeekToolCall> toolCalls,
            String finishReason
    ) {
    }
}
