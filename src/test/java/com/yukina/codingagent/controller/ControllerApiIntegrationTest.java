package com.yukina.codingagent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证应用真实 Spring MVC 路由和 JSON 契约。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:controller-api;DB_CLOSE_DELAY=-1",
        "agent.conversation-workspace.storage-root=target/controller-api-conversations",
        "agent.workspace.root=target/controller-api-runtime",
        "agent.command.allowed-executables[0]=java"
})
@AutoConfigureMockMvc
class ControllerApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createsChatAndCodeConversationsWithoutWorkspaceRegistration() throws Exception {
        mockMvc.perform(post("/api/conversations")
                        .contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Plain chat\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Plain chat"))
                .andExpect(jsonPath("$.mode").value("CHAT"))
                .andExpect(jsonPath("$.artifactAvailable").value(false));

        mockMvc.perform(post("/api/conversations")
                        .contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"API project\",\"mode\":\"CODE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("CODE"));

        mockMvc.perform(get("/api/conversations").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("API project"));
    }

    /** 环境接口应返回稳定时间戳和工具能力列表。 */
    @Test
    void exposesExecutionEnvironmentSnapshot() throws Exception {
        mockMvc.perform(get("/api/environment").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkedAt").isNotEmpty())
                .andExpect(jsonPath("$.tools").isArray())
                .andExpect(jsonPath("$.tools[0].id").value("java"))
                .andExpect(jsonPath("$.tools[0].available").isBoolean());
    }
}
