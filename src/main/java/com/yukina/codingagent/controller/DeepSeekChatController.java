package com.yukina.codingagent.controller;

import com.yukina.codingagent.deepseek.DeepSeekChatRequest;
import com.yukina.codingagent.deepseek.DeepSeekChatResponse;
import com.yukina.codingagent.deepseek.DeepSeekClient;
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

    public DeepSeekChatController(DeepSeekClient deepSeekClient) {
        this.deepSeekClient = deepSeekClient;
    }

    @PostMapping("/chat")
    @ResponseStatus(HttpStatus.OK)
    public ChatResponse chat(@RequestBody ChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message must not be blank");
        }

        DeepSeekChatResponse response = deepSeekClient.chat(List.of(
                new DeepSeekChatRequest.Message("user", request.message())
        ));

        return new ChatResponse(
                response.id(),
                response.model(),
                response.firstContent(),
                response.usage()
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
}
