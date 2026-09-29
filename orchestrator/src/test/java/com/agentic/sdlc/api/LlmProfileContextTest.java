package com.agentic.sdlc.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.agentic.sdlc.agent.AgentRegistry;
import com.agentic.sdlc.llm.LlmClient;
import com.agentic.sdlc.llm.ReplayLlmClient;
import com.agentic.sdlc.workflow.NodeDefinition;
import com.agentic.sdlc.workflow.WorkflowCatalog;

/** The llm profile wires every agent the real workflow references (replay mode, so no key is needed). */
@SpringBootTest(properties = {
        "sdlc.llm.mode=replay",
        "spring.datasource.url=jdbc:h2:mem:llm-${random.uuid};DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("llm")
class LlmProfileContextTest {

    @Autowired
    WorkflowCatalog catalog;
    @Autowired
    AgentRegistry agents;
    @Autowired
    LlmClient llm;

    @Test
    void loadsTheFullWorkflowWithRealAgents() {
        var workflow = catalog.get("sdlc");

        assertThat(workflow.usesWorkspace()).isTrue();
        assertThat(workflow.topologicalOrder()).extracting(NodeDefinition::id).containsExactly(
                "requirements", "plan", "impact_analysis", "design", "implement", "tests", "docs",
                "security_review", "review", "release_readiness");
        assertThat(agents.names()).contains("ImplementerAgent", "ImplementerFallbackAgent", "ReleaseAgent");
        assertThat(llm).isInstanceOf(ReplayLlmClient.class);
    }
}
