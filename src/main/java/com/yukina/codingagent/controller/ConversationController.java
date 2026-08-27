package com.yukina.codingagent.controller;

import com.yukina.codingagent.conversation.Conversation;
import com.yukina.codingagent.conversation.ConversationService;
import com.yukina.codingagent.conversation.MessagePage;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Conversation create(@RequestBody(required = false) CreateConversationRequest request) {
        return conversationService.create(request == null ? null : request.title());
    }

    @GetMapping
    public List<Conversation> list(@RequestParam(defaultValue = "20") int limit) {
        return conversationService.list(limit);
    }

    @GetMapping("/{conversationId}")
    public Conversation get(@PathVariable String conversationId) {
        return conversationService.get(conversationId);
    }

    @PatchMapping("/{conversationId}")
    public Conversation rename(
            @PathVariable String conversationId,
            @RequestBody RenameConversationRequest request
    ) {
        return conversationService.rename(conversationId, request == null ? null : request.title());
    }

    @GetMapping("/{conversationId}/messages")
    public MessagePage messages(
            @PathVariable String conversationId,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return conversationService.messages(conversationId, beforeId, limit);
    }

    @DeleteMapping("/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String conversationId) {
        conversationService.delete(conversationId);
    }

    public record CreateConversationRequest(String title) {
    }

    public record RenameConversationRequest(String title) {
    }
}
