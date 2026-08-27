package com.yukina.codingagent.controller;

import com.yukina.codingagent.agent.AgentLoop;
import com.yukina.codingagent.agent.AgentRunResult;
import com.yukina.codingagent.conversation.ConversationAgentService;
import com.yukina.codingagent.conversation.ConversationChatResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/agent")
public class AgentController {

    private final AgentLoop agentLoop;
    private final ConversationAgentService conversationAgentService;

    public AgentController(AgentLoop agentLoop, ConversationAgentService conversationAgentService) {
        this.agentLoop = agentLoop;
        this.conversationAgentService = conversationAgentService;
    }

    @PostMapping("/run")
    @ResponseStatus(HttpStatus.OK)
    public AgentRunResult run(@RequestBody AgentRequest request) {
        if (request == null || request.task() == null || request.task().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "task must not be blank");
        }
        return agentLoop.run(request.task());
    }

    @PostMapping("/chat")
    @ResponseStatus(HttpStatus.OK)
    public ConversationChatResult chat(@RequestBody ConversationChatRequest request) {
        if (request == null || request.task() == null || request.task().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "task must not be blank");
        }
        return conversationAgentService.chat(request.conversationId(), request.task());
    }

    public record AgentRequest(String task) {
    }

    public record ConversationChatRequest(String conversationId, String task) {
    }
}
