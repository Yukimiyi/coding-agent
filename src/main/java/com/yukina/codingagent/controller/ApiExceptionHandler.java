package com.yukina.codingagent.controller;

import com.yukina.codingagent.agent.run.AgentRunConflictException;
import com.yukina.codingagent.agent.run.AgentRunNotFoundException;
import com.yukina.codingagent.conversation.exception.ConversationNotFoundException;
import com.yukina.codingagent.workspace.exception.WorkspaceConflictException;
import com.yukina.codingagent.workspace.exception.WorkspaceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 将业务异常转换为统一的 RFC 9457 Problem Detail 响应。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * 将会话不存在异常映射为 HTTP 404。
     */
    @ExceptionHandler({
            ConversationNotFoundException.class,
            AgentRunNotFoundException.class,
            WorkspaceNotFoundException.class
    })
    public ProblemDetail handleNotFound(RuntimeException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    /**
     * 将同一会话重复运行等状态冲突映射为 HTTP 409。
     */
    @ExceptionHandler({AgentRunConflictException.class, WorkspaceConflictException.class})
    public ProblemDetail handleConflict(RuntimeException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    /**
     * 将参数校验异常映射为 HTTP 400。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }
}
