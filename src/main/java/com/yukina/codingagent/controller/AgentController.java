package com.yukina.codingagent.controller;

import com.yukina.codingagent.agent.AgentLoop;
import com.yukina.codingagent.agent.AgentRunResult;
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

    public AgentController(AgentLoop agentLoop) {
        this.agentLoop = agentLoop;
    }

    @PostMapping("/run")
    @ResponseStatus(HttpStatus.OK)
    public AgentRunResult run(@RequestBody AgentRequest request) {
        if (request == null || request.task() == null || request.task().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "task must not be blank");
        }
        return agentLoop.run(request.task());
    }

    public record AgentRequest(String task) {
    }
}
