package com.yukina.codingagent.controller;

import com.yukina.codingagent.agent.run.AgentRunAccepted;
import com.yukina.codingagent.agent.run.AgentRunService;
import com.yukina.codingagent.agent.run.AgentRunSnapshot;
import com.yukina.codingagent.conversation.model.ConversationMode;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 暴露异步 Agent 任务的提交、状态、SSE 和取消接口。
 */
@RestController
@RequestMapping("/agent/runs")
public class AgentRunController {

    private final AgentRunService agentRunService;

    /**
     * 创建异步任务控制器。
     *
     * @param agentRunService 异步运行生命周期服务
     */
    public AgentRunController(AgentRunService agentRunService) {
        this.agentRunService = agentRunService;
    }

    /**
     * 提交任务并立即返回 runId，不等待 AgentLoop 完成。
     *
     * @param request 幂等请求 ID、会话模式和任务文本
     * @return 已接受运行的标识和初始状态
     * @throws IllegalArgumentException 请求体为空或字段不合法时抛出
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AgentRunAccepted submit(@RequestBody AgentRunRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body must not be null");
        }
        return agentRunService.submit(
                request.requestId(),
                request.conversationId(),
                request.mode(),
                request.task()
        );
    }

    /**
     * 查询异步任务的当前完整快照。
     *
     * @param runId 运行 ID
     * @return 状态、事件轨迹和可选最终结果
     */
    @GetMapping("/{runId}")
    public AgentRunSnapshot get(@PathVariable String runId) {
        return agentRunService.get(runId);
    }

    /**
     * 查询会话当前活跃任务；不存在时返回 HTTP 204。
     *
     * @param conversationId 会话 ID
     * @return 活跃运行响应，或无内容响应
     */
    @GetMapping("/active")
    public ResponseEntity<AgentRunSnapshot> active(@RequestParam String conversationId) {
        AgentRunSnapshot snapshot = agentRunService.findActive(conversationId);
        return snapshot == null
                ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(snapshot);
    }

    /**
     * 订阅任务事件，并根据 Last-Event-ID 重放断线期间的事件。
     *
     * @param runId 运行 ID
     * @param lastEventId SSE 标准断点事件序号，可为空
     * @param afterSequence 查询参数形式的断点事件序号，可为空
     * @return 已注册重放和实时推送的 SSE 发射器
     * @throws IllegalArgumentException 事件序号不是非负整数时抛出
     */
    @GetMapping(path = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable String runId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(required = false) Long afterSequence
    ) {
        long parsedHeader = parseLastEventId(lastEventId);
        if (afterSequence != null && afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence must not be negative");
        }
        return agentRunService.subscribe(
                runId,
                afterSequence == null ? parsedHeader : Math.max(parsedHeader, afterSequence)
        );
    }

    /**
     * 幂等地请求取消任务并返回最新快照。
     *
     * @param runId 运行 ID
     * @return 取消请求后的最新运行快照
     */
    @PostMapping("/{runId}/cancel")
    public AgentRunSnapshot cancel(@PathVariable String runId) {
        return agentRunService.cancel(runId);
    }

    /**
     * 解析可选的 SSE 事件序号。
     *
     * @param value Last-Event-ID 请求头文本
     * @return 非负事件序号；空值返回零
     * @throws IllegalArgumentException 文本不是非负整数时抛出
     */
    private static long parseLastEventId(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new IllegalArgumentException("Last-Event-ID must not be negative");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Last-Event-ID must be a number");
        }
    }

    /**
     * 异步 Agent 任务提交请求。
     *
     * @param requestId 客户端幂等请求 ID
     * @param conversationId 可选已有会话 ID
     * @param mode 创建新会话时使用的模式
     * @param task 任务文本
     */
    public record AgentRunRequest(
            String requestId,
            String conversationId,
            ConversationMode mode,
            String task
    ) {
    }
}
