package com.yukina.codingagent.agent.reflection;

import com.yukina.codingagent.agent.AgentRunResult;
import com.yukina.codingagent.deepseek.DeepSeekChatResponse;
import com.yukina.codingagent.deepseek.DeepSeekClient;
import com.yukina.codingagent.deepseek.DeepSeekMessage;
import com.yukina.codingagent.deepseek.DeepSeekToolDefinition;
import com.yukina.codingagent.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证无工具 Reflection 的证据汇总与结构化输出解析。 */
class DeepSeekReflectionReviewerTest {

    /** 验证审查调用不携带工具，并解析 Markdown 围栏内的 REVISE JSON。 */
    @Test
    void reviewsBoundedEvidenceWithoutTools() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        when(client.chat(anyList(), anyList())).thenReturn(response(
                "```json\n{\"verdict\":\"REVISE\",\"summary\":\"测试失败\","
                        + "\"issues\":[\"修复失败测试\"]}\n```"
        ));
        DeepSeekReflectionReviewer reviewer = new DeepSeekReflectionReviewer(
                client,
                new ObjectMapper(),
                new ReflectionProperties(1, 8000, "Review and return JSON.")
        );

        ReflectionReview review = reviewer.review(
                "修复这个项目",
                "Implemented the change.",
                List.of(
                        step("write_file", true, "{\"path\":\"src/Main.java\"}", "written", null),
                        step(
                                "execute_command",
                                false,
                                "{\"command\":[\"java\",\"Main\"]}",
                                "{\"exitCode\":1}",
                                new ToolExecutionResult.Error("COMMAND_FAILED", "exit code 1", Map.of())
                        )
                ),
                null
        );

        assertEquals(ReflectionFeedback.Verdict.REVISE, review.feedback().verdict());
        assertEquals(List.of("修复失败测试"), review.feedback().issues());
        assertTrue(review.feedback().revisionInstruction(true).contains("用中文给出新的最终回答"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeepSeekMessage>> messages = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeepSeekToolDefinition>> tools = ArgumentCaptor.forClass(List.class);
        verify(client).chat(messages.capture(), tools.capture());
        assertTrue(tools.getValue().isEmpty());
        assertTrue(messages.getValue().getFirst().content().contains("Simplified Chinese"));
        assertTrue(messages.getValue().get(1).content().contains("src/Main.java"));
        assertTrue(messages.getValue().get(1).content().contains("COMMAND_FAILED"));
    }

    /** 创建单条测试工具轨迹。 */
    private static AgentRunResult.ToolStep step(
            String toolName,
            boolean success,
            String arguments,
            String content,
            ToolExecutionResult.Error error
    ) {
        return new AgentRunResult.ToolStep(
                1,
                "call-" + toolName,
                toolName,
                arguments,
                false,
                success,
                content,
                false,
                error
        );
    }

    /** 创建包含审查文本的测试响应。 */
    private static DeepSeekChatResponse response(String content) {
        return new DeepSeekChatResponse(
                "reflection-id",
                "deepseek-test",
                List.of(new DeepSeekChatResponse.Choice(
                        0,
                        DeepSeekMessage.assistant(content, null, null),
                        "stop"
                )),
                new DeepSeekChatResponse.Usage(3, 2, 5)
        );
    }
}
