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
        "agent.workspace-registry.storage-root=target/controller-api-workspaces",
        "agent.workspace.root=target/controller-api-runtime"
})
@AutoConfigureMockMvc
class ControllerApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createsWorkspaceAndToolFreeConversation() throws Exception {
        mockMvc.perform(post("/api/workspaces")
                        .contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"API project\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("API project"))
                .andExpect(jsonPath("$.type").value("MANAGED"));

        mockMvc.perform(post("/api/conversations")
                        .contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Plain chat\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Plain chat"))
                .andExpect(jsonPath("$.workspaceId").doesNotExist());

        mockMvc.perform(get("/api/conversations").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Plain chat"));
    }
}
