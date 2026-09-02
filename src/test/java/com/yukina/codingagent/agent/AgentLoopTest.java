package com.yukina.codingagent.agent;

import com.yukina.codingagent.agent.perception.ProjectSnapshot;
import com.yukina.codingagent.agent.perception.ProjectSnapshotProvider;
import com.yukina.codingagent.agent.plan.AgentPlan;
import com.yukina.codingagent.agent.plan.PlanEvidenceType;
import com.yukina.codingagent.agent.plan.PlanCoordinator;
import com.yukina.codingagent.agent.plan.PlanStep;
import com.yukina.codingagent.agent.plan.PlanStepStatus;
import com.yukina.codingagent.agent.plan.PlanningProperties;
import com.yukina.codingagent.agent.plan.PlanningResult;
import com.yukina.codingagent.agent.plan.PlanningService;
import com.yukina.codingagent.agent.reflection.ReflectionFeedback;
import com.yukina.codingagent.agent.reflection.ReflectionProperties;
import com.yukina.codingagent.agent.reflection.ReflectionReview;
import com.yukina.codingagent.agent.reflection.ReflectionReviewer;
import com.yukina.codingagent.deepseek.DeepSeekChatResponse;
import com.yukina.codingagent.deepseek.DeepSeekClient;
import com.yukina.codingagent.deepseek.DeepSeekMessage;
import com.yukina.codingagent.deepseek.DeepSeekStreamObserver;
import com.yukina.codingagent.deepseek.DeepSeekToolCall;
import com.yukina.codingagent.deepseek.DeepSeekToolDefinition;
import com.yukina.codingagent.tool.AgentTool;
import com.yukina.codingagent.tool.ToolExecutor;
import com.yukina.codingagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证 Agent 循环的工具编排和停止边界。 */
class AgentLoopTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 验证工具结果会回传模型并生成最终回答。 */
    @Test
    void executesToolAndReturnsFinalAnswer() throws Exception {
        DeepSeekClient client = mock(DeepSeekClient.class);
        when(client.chatStream(anyList(), anyList(), any())).thenReturn(
                response(toolMessage(call("echo", "call-1", "{\"text\":\"hello\"}")), "tool_calls", 5),
                response(DeepSeekMessage.assistant("Echo completed.", null, null), "stop", 7)
        );
        AgentLoop agentLoop = loop(client, 4, 4);

        AgentRunResult result = agentLoop.run("Echo hello");

        assertTrue(result.completed());
        assertEquals(AgentRunResult.StopReason.COMPLETED, result.stopReason());
        assertEquals("Echo completed.", result.answer());
        assertEquals(2, result.iterations());
        assertEquals(1, result.toolSteps().size());
        assertTrue(result.toolSteps().getFirst().success());
        assertEquals(12, result.usage().totalTokens());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeepSeekMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(client, times(2)).chatStream(messages.capture(), anyList(), any());
        List<DeepSeekMessage> secondRequest = messages.getAllValues().get(1);
        assertEquals(List.of("system", "user", "assistant", "tool"), secondRequest.stream()
                .map(DeepSeekMessage::role)
                .toList());
        assertEquals("call-1", secondRequest.get(3).toolCallId());
        assertEquals("{\"echo\":\"hello\"}", secondRequest.get(3).content());
    }

    /** 验证上下文管理器生成的前置滚动摘要会进入真实模型请求。 */
    @Test
    void acceptsLeadingConversationMemorySystemMessage() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        when(client.chatStream(anyList(), anyList(), any())).thenReturn(
                response(DeepSeekMessage.assistant("Done.", null, null), "stop", 3)
        );
        List<DeepSeekMessage> history = List.of(
                DeepSeekMessage.system("Structured historical memory."),
                DeepSeekMessage.user("Earlier request"),
                DeepSeekMessage.assistant("Earlier answer", null, null)
        );

        AgentRunResult result = loop(client, 2, 2).run("Current request", history);

        assertTrue(result.completed());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeepSeekMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(client).chatStream(messages.capture(), anyList(), any());
        assertEquals(
                List.of("system", "system", "user", "assistant", "user"),
                messages.getValue().stream().map(DeepSeekMessage::role).toList()
        );
        assertEquals("Structured historical memory.", messages.getValue().get(1).content());
    }

    /** 验证工具失败可作为消息反馈给模型继续恢复。 */
    @Test
    void feedsToolFailureBackToModelForRecovery() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        DeepSeekToolCall missingTool = call("missing", "call-missing", "{}");
        when(client.chatStream(anyList(), anyList(), any())).thenReturn(
                response(toolMessage(missingTool), "tool_calls", 2),
                response(DeepSeekMessage.assistant("I could not use that tool.", null, null), "stop", 3)
        );

        AgentRunResult result = loop(client, 4, 4).run("Use a missing tool");

        assertTrue(result.completed());
        assertFalse(result.toolSteps().getFirst().success());
        assertEquals("TOOL_NOT_FOUND", result.toolSteps().getFirst().error().code());
    }

    /** 验证完全相同的确定性工具失败不会一直消耗循环轮数。 */
    @Test
    void stopsAfterRepeatedIdenticalToolFailure() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        when(client.chatStream(anyList(), anyList(), any())).thenReturn(
                response(toolMessage(call("missing", "call-missing-1", "{}")), "tool_calls", 2),
                response(toolMessage(call("missing", "call-missing-2", "{}")), "tool_calls", 2)
        );

        AgentRunResult result = loop(client, 16, 4).run("Repeat a missing tool");

        assertFalse(result.completed());
        assertEquals(AgentRunResult.StopReason.REPEATED_TOOL_FAILURE, result.stopReason());
        assertEquals(2, result.iterations());
        assertEquals(2, result.toolSteps().size());
        verify(client, times(2)).chatStream(anyList(), anyList(), any());
    }

    /** 验证达到最大循环轮数后停止执行。 */
    @Test
    void stopsAtMaximumIterations() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        DeepSeekChatResponse repeatedCall = response(
                toolMessage(call("echo", "call-repeat", "{\"text\":\"again\"}")),
                "tool_calls",
                1
        );
        when(client.chatStream(anyList(), anyList(), any())).thenReturn(repeatedCall);

        AgentRunResult result = loop(client, 2, 4).run("Keep calling tools");

        assertFalse(result.completed());
        assertEquals(AgentRunResult.StopReason.MAX_ITERATIONS, result.stopReason());
        assertEquals(2, result.iterations());
        assertEquals(2, result.toolSteps().size());
    }

    /** 验证因输出长度限制截断的文本不会被误判为完整回答。 */
    @Test
    void reportsTruncatedModelResponse() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        when(client.chatStream(anyList(), anyList(), any())).thenReturn(
                response(DeepSeekMessage.assistant("Partial answer", null, null), "length", 3)
        );

        AgentRunResult result = loop(client, 4, 4).run("Give a long answer");

        assertFalse(result.completed());
        assertEquals(AgentRunResult.StopReason.RESPONSE_TRUNCATED, result.stopReason());
        assertEquals("Partial answer", result.answer());
    }

    /** 验证单轮工具调用数超过上限时拒绝执行。 */
    @Test
    void refusesTooManyToolCallsInOneIteration() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        DeepSeekMessage assistant = DeepSeekMessage.assistant(null, null, List.of(
                call("echo", "call-1", "{}"),
                call("echo", "call-2", "{}")
        ));
        when(client.chatStream(anyList(), anyList(), any())).thenReturn(response(assistant, "tool_calls", 1));

        AgentRunResult result = loop(client, 4, 1).run("Call too many tools");

        assertFalse(result.completed());
        assertEquals(AgentRunResult.StopReason.TOOL_CALL_LIMIT, result.stopReason());
        assertTrue(result.toolSteps().isEmpty());
    }

    /** 验证观察器按实际执行顺序收到公开阶段事件。 */
    @Test
    void publishesIterationAndToolEventsInOrder() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        when(client.chatStream(anyList(), anyList(), any())).thenReturn(
                response(
                        DeepSeekMessage.assistant(
                                "I will run the echo tool.",
                                null,
                                List.of(call("echo", "call-live", "{\"text\":\"live\"}"))
                        ),
                        "tool_calls",
                        2
                ),
                response(DeepSeekMessage.assistant("Done.", null, null), "stop", 2)
        );
        List<String> events = new java.util.ArrayList<>();
        AgentLoopObserver observer = new AgentLoopObserver() {
            @Override
            public void onIterationStarted(int iteration) {
                events.add("iteration:" + iteration);
            }

            @Override
            public void onProgress(int iteration, String summary) {
                events.add("progress:" + iteration);
            }

            @Override
            public void onThought(int iteration, String summary) {
                events.add("thought:" + iteration + ":" + summary);
            }

            @Override
            public void onModelResponse(int iteration, String model, int toolCallCount) {
                events.add("model:" + iteration + ":" + toolCallCount);
            }

            @Override
            public void onToolStarted(int iteration, String id, String name, String arguments) {
                events.add("tool-start:" + name);
            }

            @Override
            public void onToolCompleted(AgentRunResult.ToolStep toolStep) {
                events.add("tool-end:" + toolStep.toolName());
            }
        };

        loop(client, 4, 4).run("Run live", List.of(), observer, AgentRunCancellation.NONE);

        assertEquals(List.of(
                "iteration:1",
                "progress:1",
                "thought:1:I will run the echo tool.",
                "model:1:1",
                "tool-start:echo",
                "tool-end:echo",
                "iteration:2",
                "progress:2",
                "model:2:0"
        ), events);
    }

    /** 验证模型公开回答增量会原样转发给 Agent 观察器。 */
    @Test
    void forwardsStreamedAnswerDeltas() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        when(client.chatStream(anyList(), anyList(), any())).thenAnswer(invocation -> {
            DeepSeekStreamObserver streamObserver = invocation.getArgument(2);
            streamObserver.onContentDelta("Done");
            streamObserver.onContentDelta(".");
            return response(DeepSeekMessage.assistant("Done.", null, null), "stop", 2);
        });
        List<String> deltas = new java.util.ArrayList<>();
        AgentLoopObserver observer = new AgentLoopObserver() {
            @Override
            public void onAnswerDelta(int iteration, String delta) {
                deltas.add(iteration + ":" + delta);
            }
        };

        AgentRunResult result = loop(client, 4, 4).run(
                "Stream the answer",
                List.of(),
                observer,
                AgentRunCancellation.NONE
        );

        assertEquals(List.of("1:Done", "1:."), deltas);
        assertEquals("Done.", result.answer());
    }

    /** 验证预先取消的任务不会继续调用模型。 */
    @Test
    void stopsBeforeModelCallWhenCancelled() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        AtomicBoolean cancelled = new AtomicBoolean(true);

        assertThrows(
                AgentRunCancelledException.class,
                () -> loop(client, 4, 4).run("Do not run", List.of(), AgentLoopObserver.NONE, cancelled::get)
        );
        verifyNoInteractions(client);
    }

    /** 验证纯对话不会向模型暴露工作空间工具。 */
    @Test
    void omitsToolsForChatOnlyConversation() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        when(client.chatStream(anyList(), anyList(), any())).thenReturn(
                response(DeepSeekMessage.assistant("Use a list to store the values.", null, null), "stop", 3)
        );

        AgentRunResult result = loop(client, 4, 4).runWithoutTools(
                "How should I model this?",
                List.of(),
                AgentLoopObserver.NONE,
                AgentRunCancellation.NONE
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeepSeekToolDefinition>> tools = ArgumentCaptor.forClass(List.class);
        verify(client).chatStream(anyList(), tools.capture(), any());
        assertTrue(tools.getValue().isEmpty());
        assertTrue(result.completed());
        assertTrue(result.toolSteps().isEmpty());
    }

    /** 中文任务应在主系统提示词中获得明确的简体中文输出约束。 */
    @Test
    void instructsChatResponseToFollowChineseTaskLanguage() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        when(client.chatStream(anyList(), anyList(), any())).thenReturn(
                response(DeepSeekMessage.assistant("可以使用列表保存这些值。", null, null), "stop", 3)
        );

        loop(client, 4, 4).runWithoutTools(
                "应该如何保存这些值？",
                List.of(),
                AgentLoopObserver.NONE,
                AgentRunCancellation.NONE
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeepSeekMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(client).chatStream(messages.capture(), anyList(), any());
        assertTrue(messages.getValue().getFirst().content().contains("Simplified Chinese"));
    }

    /** 中文任务收到纯英文最终说明时应清除并要求模型重写一次。 */
    @Test
    void rewritesEnglishFinalAnswerForChineseTask() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        when(client.chatStream(anyList(), anyList(), any())).thenReturn(
                response(DeepSeekMessage.assistant("Updated the project successfully.", null, null), "stop", 3),
                response(DeepSeekMessage.assistant("项目已经成功更新。", null, null), "stop", 3)
        );
        List<Integer> resets = new java.util.ArrayList<>();
        AgentLoopObserver observer = new AgentLoopObserver() {
            @Override
            public void onAnswerReset(int iteration) {
                resets.add(iteration);
            }
        };

        AgentRunResult result = loop(client, 4, 4).runWithoutTools(
                "请更新这个项目",
                List.of(),
                observer,
                AgentRunCancellation.NONE
        );

        assertEquals("项目已经成功更新。", result.answer());
        assertEquals(2, result.iterations());
        assertEquals(List.of(1), resets);
        verify(client, times(2)).chatStream(anyList(), anyList(), any());
    }

    /** 验证 CODE 会话不会把未写入项目的代码块直接当作最终成果。 */
    @Test
    void requestsFileChangesWhenModelReturnsCodeWithoutMutation() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        when(client.chatStream(anyList(), anyList(), any())).thenReturn(
                response(DeepSeekMessage.assistant("```java\nclass Main {}\n```", null, null), "stop", 3),
                response(DeepSeekMessage.assistant("The project files were updated.", null, null), "stop", 2)
        );

        AgentRunResult result = loop(client, 4, 4).run("Create Main.java");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeepSeekMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(client, times(2)).chatStream(messages.capture(), anyList(), any());
        List<DeepSeekMessage> retry = messages.getAllValues().get(1);
        assertEquals("user", retry.getLast().role());
        assertTrue(retry.getLast().content().contains("Apply the requested code with the file tools"));
        assertEquals("The project files were updated.", result.answer());
        assertEquals(2, result.iterations());
    }

    /** 工具会话的系统提示词应包含当前执行环境能力。 */
    @Test
    void includesExecutionEnvironmentInToolSystemPrompt() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        when(client.chatStream(anyList(), anyList(), any())).thenReturn(
                response(DeepSeekMessage.assistant("Done.", null, null), "stop", 2)
        );

        loop(client, 4, 4).run("Inspect the project");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeepSeekMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(client).chatStream(messages.capture(), anyList(), any());
        assertTrue(messages.getValue().getFirst().content().contains("Available: java"));
    }

    /** 验证文件修改后的候选回答通过反思时可直接结束，并累计审查用量。 */
    @Test
    void completesWhenReflectionPasses() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        ReflectionReviewer reviewer = mock(ReflectionReviewer.class);
        when(client.chatStream(anyList(), anyList(), any())).thenReturn(
                response(toolMessage(call(
                        "write_file",
                        "call-write",
                        "{\"path\":\"Main.java\",\"content\":\"class Main {}\"}"
                )), "tool_calls", 2),
                response(DeepSeekMessage.assistant("Created Main.java.", null, null), "stop", 3)
        );
        when(reviewer.review(anyString(), anyString(), anyList(), any())).thenReturn(new ReflectionReview(
                new ReflectionFeedback(ReflectionFeedback.Verdict.PASS, "实现与现有证据一致", List.of()),
                new DeepSeekChatResponse.Usage(3, 1, 4)
        ));

        AgentRunResult result = loop(client, reviewer, 4, 4).run("Create Main.java");

        assertTrue(result.completed());
        assertEquals("Created Main.java.", result.answer());
        assertEquals(9, result.usage().totalTokens());
        verify(reviewer).review(anyString(), anyString(), anyList(), any());
        verify(client, times(2)).chatStream(anyList(), anyList(), any());
    }

    /** 验证 REVISE 反馈会作为新任务回到 ReAct，而不会成为公开思维链。 */
    @Test
    void resumesReactLoopWhenReflectionRequestsRevision() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        ReflectionReviewer reviewer = mock(ReflectionReviewer.class);
        when(client.chatStream(anyList(), anyList(), any())).thenReturn(
                response(toolMessage(call(
                        "write_file",
                        "call-write",
                        "{\"path\":\"Main.java\",\"content\":\"class Main {}\"}"
                )), "tool_calls", 2),
                response(DeepSeekMessage.assistant("Initial result.", null, null), "stop", 2),
                response(DeepSeekMessage.assistant("Corrected and verified result.", null, null), "stop", 2)
        );
        when(reviewer.review(anyString(), anyString(), anyList(), any())).thenReturn(new ReflectionReview(
                new ReflectionFeedback(
                        ReflectionFeedback.Verdict.REVISE,
                        "缺少必要验证",
                        List.of("运行项目测试并依据真实结果总结")
                ),
                new DeepSeekChatResponse.Usage(2, 1, 3)
        ));

        AgentRunResult result = loop(client, reviewer, 5, 4).run("Create and verify Main.java");

        assertTrue(result.completed());
        assertEquals("Corrected and verified result.", result.answer());
        assertEquals(3, result.iterations());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeepSeekMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(client, times(3)).chatStream(messages.capture(), anyList(), any());
        DeepSeekMessage feedbackMessage = messages.getAllValues().get(2).getLast();
        assertEquals("user", feedbackMessage.role());
        assertTrue(feedbackMessage.content().contains("缺少必要验证"));
        assertTrue(feedbackMessage.content().contains("运行项目测试"));
    }

    /** 验证纯聊天即使有候选回答也不会触发 Reflection。 */
    @Test
    void doesNotReflectInChatOnlyMode() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        ReflectionReviewer reviewer = mock(ReflectionReviewer.class);
        when(client.chatStream(anyList(), anyList(), any())).thenReturn(
                response(DeepSeekMessage.assistant("Chat answer.", null, null), "stop", 2)
        );

        AgentRunResult result = loop(client, reviewer, 4, 4).runWithoutTools(
                "Discuss an approach",
                List.of(),
                AgentLoopObserver.NONE,
                AgentRunCancellation.NONE
        );

        assertTrue(result.completed());
        verifyNoInteractions(reviewer);
    }

    /** 验证 CODE 任务先规划，再通过 update_plan 证据审批后进入 Reflection。 */
    @Test
    void executesPlanGuidedReactBeforeReflection() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        ReflectionReviewer reviewer = mock(ReflectionReviewer.class);
        PlanningService planningService = mock(PlanningService.class);
        ProjectSnapshotProvider snapshotProvider = mock(ProjectSnapshotProvider.class);
        AgentPlan initialPlan = new AgentPlan(
                "Create Main.java",
                List.of(new PlanStep(
                        "step-1",
                        "Create the source file",
                        "write_file succeeds",
                        PlanEvidenceType.MUTATION,
                        PlanStepStatus.IN_PROGRESS,
                        0,
                        List.of(),
                        null
                )),
                List.of("Main.java exists")
        );
        ProjectSnapshot snapshot = new ProjectSnapshot(true, List.of(), Map.of(), "Available: java", false);
        when(snapshotProvider.capture()).thenReturn(snapshot);
        when(planningService.createPlan(anyString(), anyList(), any())).thenReturn(new PlanningResult(
                initialPlan,
                new DeepSeekChatResponse.Usage(2, 1, 3),
                false,
                "执行计划已创建"
        ));
        when(client.chatStream(anyList(), anyList(), any())).thenReturn(
                response(toolMessage(call(
                        "write_file",
                        "call-write-plan",
                        "{\"path\":\"Main.java\",\"content\":\"class Main {}\"}"
                )), "tool_calls", 2),
                response(toolMessage(call(
                        "update_plan",
                        "call-plan-update",
                        "{\"steps\":[{\"id\":\"step-1\",\"status\":\"COMPLETED\","
                                + "\"evidenceToolCallIds\":[\"call-write-plan\"]}],"
                                + "\"summary\":\"Source file created\"}"
                )), "tool_calls", 2),
                response(DeepSeekMessage.assistant("Created Main.java.", null, null), "stop", 2)
        );
        when(reviewer.review(anyString(), anyString(), anyList(), any())).thenReturn(new ReflectionReview(
                new ReflectionFeedback(ReflectionFeedback.Verdict.PASS, "计划和证据一致", List.of()),
                new DeepSeekChatResponse.Usage(2, 1, 3)
        ));
        List<String> events = new java.util.ArrayList<>();
        AgentLoopObserver observer = new AgentLoopObserver() {
            @Override
            public void onPerceptionCompleted(ProjectSnapshot value) {
                events.add("perception");
            }

            @Override
            public void onPlanCreated(AgentPlan plan, boolean fallbackUsed, String notice) {
                events.add("plan-created:" + plan.steps().getFirst().status());
            }

            @Override
            public void onPlanUpdated(AgentPlan plan, String summary) {
                events.add("plan-updated:" + plan.steps().getFirst().status());
            }
        };

        AgentRunResult result = loop(
                client,
                reviewer,
                planningService,
                snapshotProvider,
                true,
                5,
                4
        ).run("Create Main.java", List.of(), observer, AgentRunCancellation.NONE);

        assertTrue(result.completed());
        assertEquals(PlanStepStatus.COMPLETED, result.plan().steps().getFirst().status());
        assertEquals(1, result.reflection().rounds());
        assertEquals(0, result.reflection().revisions());
        assertEquals(List.of("perception", "plan-created:IN_PROGRESS", "plan-updated:COMPLETED"), events);
        assertEquals(12, result.usage().totalTokens());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeepSeekToolDefinition>> tools = ArgumentCaptor.forClass(List.class);
        verify(client, times(3)).chatStream(anyList(), tools.capture(), any());
        assertTrue(tools.getAllValues().getFirst().stream()
                .anyMatch(tool -> "update_plan".equals(tool.function().name())));
    }

    /** 使用测试边界配置创建 Agent 循环。 */
    private AgentLoop loop(DeepSeekClient client, int maxIterations, int maxToolCalls) {
        return loop(client, mock(ReflectionReviewer.class), maxIterations, maxToolCalls);
    }

    /**
     * 使用指定反思审查器和测试边界创建 Agent 循环。
     *
     * @param client 模拟模型客户端
     * @param reviewer 模拟反思审查器
     * @param maxIterations 最大 ReAct 轮数
     * @param maxToolCalls 单轮最大工具调用数
     * @return 可执行 echo 与 write_file 的测试 AgentLoop
     */
    private AgentLoop loop(
            DeepSeekClient client,
            ReflectionReviewer reviewer,
            int maxIterations,
            int maxToolCalls
    ) {
        return loop(
                client,
                reviewer,
                mock(PlanningService.class),
                mock(ProjectSnapshotProvider.class),
                false,
                maxIterations,
                maxToolCalls
        );
    }

    /** 使用可选规划依赖创建完整测试循环。 */
    private AgentLoop loop(
            DeepSeekClient client,
            ReflectionReviewer reviewer,
            PlanningService planningService,
            ProjectSnapshotProvider snapshotProvider,
            boolean planningEnabled,
            int maxIterations,
            int maxToolCalls
    ) {
        AgentTool echo = new AgentTool() {
            /** {@inheritDoc} */
            @Override
            public DeepSeekToolDefinition definition() {
                return DeepSeekToolDefinition.function(
                        "echo",
                        "Echo text",
                        Map.of("type", "object", "properties", Map.of())
                );
            }

            /** {@inheritDoc} */
            @Override
            public String execute(JsonNode arguments) throws Exception {
                return objectMapper.writeValueAsString(Map.of("echo", arguments.path("text").asText()));
            }
        };
        AgentTool writeFile = new AgentTool() {
            /** {@inheritDoc} */
            @Override
            public DeepSeekToolDefinition definition() {
                return DeepSeekToolDefinition.function(
                        "write_file",
                        "Write a test file",
                        Map.of("type", "object", "properties", Map.of())
                );
            }

            /** {@inheritDoc} */
            @Override
            public String execute(JsonNode arguments) throws Exception {
                return objectMapper.writeValueAsString(Map.of(
                        "path", arguments.path("path").asText(),
                        "written", true
                ));
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(echo, writeFile));
        ToolExecutor executor = new ToolExecutor(registry, objectMapper);
        AgentLoopProperties properties = new AgentLoopProperties(
                maxIterations,
                maxToolCalls,
                100,
                "You are a test coding agent."
        );
        return new AgentLoop(
                client,
                registry,
                executor,
                properties,
                () -> "Detected execution environment. Available: java. Unavailable: none.",
                reviewer,
                new ReflectionProperties(1, 10000, "Return PASS or REVISE JSON."),
                snapshotProvider,
                planningService,
                new PlanningProperties(
                        planningEnabled,
                        6,
                        10000,
                        2,
                        100,
                        4000,
                        "Return a structured plan."
                ),
                new PlanCoordinator(objectMapper)
        );
    }

    /** 创建包含单个工具调用的助手消息。 */
    private static DeepSeekMessage toolMessage(DeepSeekToolCall call) {
        return DeepSeekMessage.assistant(null, null, List.of(call));
    }

    /** 创建测试工具调用。 */
    private static DeepSeekToolCall call(String name, String id, String arguments) {
        return new DeepSeekToolCall(
                id,
                "function",
                new DeepSeekToolCall.FunctionCall(name, arguments)
        );
    }

    /** 创建测试模型响应。 */
    private static DeepSeekChatResponse response(
            DeepSeekMessage message,
            String finishReason,
            int totalTokens
    ) {
        return new DeepSeekChatResponse(
                "response-id",
                "deepseek-test",
                List.of(new DeepSeekChatResponse.Choice(0, message, finishReason)),
                new DeepSeekChatResponse.Usage(totalTokens - 1, 1, totalTokens)
        );
    }
}
