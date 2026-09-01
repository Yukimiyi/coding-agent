package com.yukina.codingagent.agent.run;

import com.yukina.codingagent.agent.AgentRunCancelledException;
import com.yukina.codingagent.agent.AgentRunResult;
import com.yukina.codingagent.agent.perception.ProjectSnapshot;
import com.yukina.codingagent.agent.plan.AgentPlan;
import com.yukina.codingagent.agent.plan.PlanStep;
import com.yukina.codingagent.agent.plan.PlanStepStatus;
import com.yukina.codingagent.agent.reflection.ReflectionFeedback;
import com.yukina.codingagent.conversation.model.ConversationChatResult;
import com.yukina.codingagent.conversation.model.Conversation;
import com.yukina.codingagent.conversation.model.ConversationMode;
import com.yukina.codingagent.conversation.service.ConversationAgentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证异步任务的幂等提交、实时轨迹和取消终态。 */
class AgentRunServiceTest {

    private ConversationAgentService conversationAgentService;
    private AgentRunService runService;

    /** 创建带短测试超时的异步任务服务。 */
    @BeforeEach
    void setUp() {
        conversationAgentService = mock(ConversationAgentService.class);
        runService = new AgentRunService(
                conversationAgentService,
                new AgentRunProperties(Duration.ofMinutes(5), 20, Duration.ofMinutes(1), 100, 10000),
                mock(AgentRunHistoryRepository.class)
        );
    }

    /** 关闭测试虚拟线程执行器。 */
    @AfterEach
    void tearDown() {
        runService.shutdown();
    }

    /** 验证提交立即返回、requestId 幂等且工具步骤进入状态快照。 */
    @Test
    void submitsIdempotentlyAndCollectsLiveToolSteps() throws Exception {
        ConversationAgentService.PreparedConversation prepared =
                new ConversationAgentService.PreparedConversation(conversation("conversation-1"), true, "test task");
        when(conversationAgentService.prepare(null, ConversationMode.CHAT, "test task")).thenReturn(prepared);
        CountDownLatch release = new CountDownLatch(1);
        when(conversationAgentService.execute(any(), any(), any())).thenAnswer(invocation -> {
            var observer = (com.yukina.codingagent.agent.AgentLoopObserver) invocation.getArgument(1);
            observer.onPerceptionCompleted(new ProjectSnapshot(true, List.of(), java.util.Map.of(), "java", false));
            observer.onPlanStarted();
            observer.onPlanCreated(plan(), false, "执行计划已创建");
            observer.onIterationStarted(1);
            observer.onProgress(1, "分析任务并规划下一步");
            observer.onThought(1, "读取项目文件并核对内容");
            observer.onToolStarted(1, "call-1", "read_file", "{\"path\":\"README.md\"}");
            observer.onToolCompleted(toolStep());
            observer.onReflectionStarted(1);
            observer.onReflectionCompleted(
                    1,
                    new ReflectionFeedback(ReflectionFeedback.Verdict.PASS, "验证证据充分", List.of())
            );
            observer.onAnswerDelta(1, "Done.");
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return new ConversationChatResult("conversation-1", true, completed());
        });

        AgentRunAccepted first = runService.submit("request-1", null, "test task");
        AgentRunAccepted duplicate = runService.submit("request-1", null, "test task");

        assertEquals(first.runId(), duplicate.runId());
        assertNotNull(first.conversationId());
        assertEquals(ConversationMode.CHAT, first.mode());
        release.countDown();
        AgentRunSnapshot snapshot = awaitTerminal(first.runId());
        assertEquals(AgentRunStatus.COMPLETED, snapshot.status());
        assertEquals(1, snapshot.toolSteps().size());
        assertEquals("read_file", snapshot.toolSteps().getFirst().toolName());
        assertEquals("Done.", snapshot.liveContent());
        assertEquals("Test plan", snapshot.plan().goal());
        assertEquals(4, snapshot.processTrace().size());
        assertEquals(AgentRunResult.ProcessType.THOUGHT, snapshot.processTrace().get(0).type());
        assertEquals("读取项目文件并核对内容", snapshot.processTrace().get(0).summary());
        assertEquals(AgentRunResult.ProcessType.ACTION, snapshot.processTrace().get(1).type());
        assertEquals(AgentRunResult.ProcessType.OBSERVATION, snapshot.processTrace().get(2).type());
        assertEquals(AgentRunResult.ProcessType.RESULT_CHECK, snapshot.processTrace().get(3).type());
        assertEquals(snapshot.processTrace(), snapshot.result().processTrace());
        assertTrue(snapshot.lastSequence() >= 7);
    }

    /** 验证取消会中断后台执行且终态不会被完成结果覆盖。 */
    @Test
    void cancelsRunningTask() throws Exception {
        ConversationAgentService.PreparedConversation prepared =
                new ConversationAgentService.PreparedConversation(conversation("conversation-2"), false, "long task");
        when(conversationAgentService.prepare("conversation-2", ConversationMode.CHAT, "long task")).thenReturn(prepared);
        CountDownLatch started = new CountDownLatch(1);
        when(conversationAgentService.execute(any(), any(), any())).thenAnswer(invocation -> {
            var cancellation = (com.yukina.codingagent.agent.AgentRunCancellation) invocation.getArgument(2);
            started.countDown();
            while (!cancellation.isCancellationRequested()) {
                Thread.sleep(10);
            }
            throw new AgentRunCancelledException();
        });

        AgentRunAccepted accepted = runService.submit("request-2", "conversation-2", "long task");
        assertTrue(started.await(1, TimeUnit.SECONDS));
        AgentRunSnapshot cancelled = runService.cancel(accepted.runId());

        assertEquals(AgentRunStatus.CANCELLED, cancelled.status());
        Thread.sleep(30);
        assertEquals(AgentRunStatus.CANCELLED, runService.get(accepted.runId()).status());
    }

    /** 等待任务进入任意终态。 */
    private AgentRunSnapshot awaitTerminal(String runId) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            AgentRunSnapshot snapshot = runService.get(runId);
            if (snapshot.status().isTerminal()) {
                return snapshot;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("run did not finish in time");
    }

    /** 创建一条测试工具轨迹。 */
    private static AgentRunResult.ToolStep toolStep() {
        return new AgentRunResult.ToolStep(
                1,
                "call-1",
                "read_file",
                "{\"path\":\"README.md\"}",
                false,
                true,
                "content",
                false,
                null
        );
    }

    /** 创建已完成的 Agent 结果。 */
    private static AgentRunResult completed() {
        return new AgentRunResult(
                "Done.",
                "deepseek-test",
                1,
                true,
                AgentRunResult.StopReason.COMPLETED,
                List.of(toolStep()),
                new AgentRunResult.Usage(1, 1, 2)
        );
    }

    /** 创建用于快照恢复断言的单步骤计划。 */
    private static AgentPlan plan() {
        return new AgentPlan(
                "Test plan",
                List.of(new PlanStep(
                        "step-1",
                        "Read project",
                        "read succeeds",
                        PlanStepStatus.IN_PROGRESS,
                        List.of(),
                        null
                )),
                List.of("Project inspected")
        );
    }

    /** 创建测试会话。 */
    private static Conversation conversation(String id) {
        return new Conversation(id, "Test", ConversationMode.CHAT, Instant.EPOCH, Instant.EPOCH);
    }
}
