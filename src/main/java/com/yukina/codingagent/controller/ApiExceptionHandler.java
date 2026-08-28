package com.yukina.codingagent.controller;

import com.yukina.codingagent.conversation.exception.ConversationNotFoundException;
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
    @ExceptionHandler(ConversationNotFoundException.class)
    public ProblemDetail handleNotFound(ConversationNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    /**
     * 将参数校验异常映射为 HTTP 400。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }
}
