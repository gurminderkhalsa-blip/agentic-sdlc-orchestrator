package com.agentic.sdlc.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.agentic.sdlc.engine.WorkflowEngine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Drives the real sdlc workflow with stub agents through the REST API, like a reviewer would. */
@SpringBootTest(properties = {
        "sdlc.agents.mode=stub",
        "sdlc.retry-backoff=0ms",
        "spring.datasource.url=jdbc:h2:mem:api-${random.uuid};DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class StubWorkflowApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    WorkflowEngine engine;

    @Test
    void fullSdlcRunWithThreeHumanCheckpoints() throws Exception {
        JsonNode started = body(mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content("""
                {"requirement": "Build a URL shortener", "scenario": "GREENFIELD", "actor": "alice"}
                """)).andExpect(status().isCreated()));
        String runId = started.path("run").path("id").asString();

        for (String expectedStage : new String[] { "requirements", "design", "release_readiness" }) {
            engine.awaitIdle(runId, Duration.ofSeconds(10));
            JsonNode view = body(mvc.perform(get("/api/runs/" + runId)).andExpect(status().isOk()));
            assertThat(view.path("run").path("status").asString()).isEqualTo("AWAITING_APPROVAL");
            JsonNode approval = view.path("pendingApprovals").get(0);
            assertThat(approval.path("nodeId").asString()).isEqualTo(expectedStage);
            mvc.perform(post("/api/approvals/" + approval.path("id").asLong() + "/approve")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"actor\": \"alice\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"));
        }
        engine.awaitIdle(runId, Duration.ofSeconds(10));

        mvc.perform(get("/api/runs/" + runId)).andExpect(jsonPath("$.run.status").value("SUCCEEDED"));
        JsonNode metrics = body(mvc.perform(get("/api/runs/" + runId + "/metrics")));
        assertThat(metrics.path("retries").asInt()).isEqualTo(1); // the stub implementer fails once
        assertThat(metrics.path("approvalsRequested").asInt()).isEqualTo(3);
        mvc.perform(get("/api/runs/" + runId + "/artifacts/release_notes")).andExpect(status().isOk());
        mvc.perform(get("/api/runs/" + runId + "/audit")).andExpect(jsonPath("$.length()").value(
                org.hamcrest.Matchers.greaterThan(20)));
    }

    @Test
    void validatesRequestsAndReportsConflicts() throws Exception {
        mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content("{\"actor\": \"alice\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/runs/nope")).andExpect(status().isNotFound());
        mvc.perform(post("/api/approvals/999/approve").contentType(MediaType.APPLICATION_JSON)
                .content("{\"actor\": \"alice\"}")).andExpect(status().isNotFound());
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
