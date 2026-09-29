package com.agentic.sdlc.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agentic.sdlc.agent.llm.ImplementerAgent;
import com.agentic.sdlc.agent.llm.LlmSupport;
import com.agentic.sdlc.agent.llm.PromptLibrary;
import com.agentic.sdlc.agent.llm.RequirementsAgent;
import com.agentic.sdlc.config.SdlcProperties;
import com.agentic.sdlc.llm.LlmRequest;
import com.agentic.sdlc.llm.LlmResponse;
import com.agentic.sdlc.state.Scenario;
import com.agentic.sdlc.support.TestProperties;
import com.agentic.sdlc.workflow.NodeDefinition;
import com.agentic.sdlc.workspace.BuildRunner;
import com.agentic.sdlc.workspace.WorkspaceService;
import com.agentic.sdlc.workspace.WorkspaceSession;

import tools.jackson.databind.json.JsonMapper;

class LlmAgentTest {

    @TempDir
    Path tmp;

    private final AtomicReference<LlmRequest> lastRequest = new AtomicReference<>();
    private final AtomicReference<String> reply = new AtomicReference<>();
    private WorkspaceService workspaces;
    private LlmSupport support;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tmp.resolve("template/src/main/java"));
        Files.writeString(tmp.resolve("template/src/main/java/App.java"), "class App {}");
        SdlcProperties properties = TestProperties.withWorkspace(tmp.resolve("ws"), tmp.resolve("template"));
        workspaces = new WorkspaceService(properties, new BuildRunner());
        support = new LlmSupport(request -> {
            lastRequest.set(request);
            return new LlmResponse(reply.get(), request.model(), 100, 50);
        }, new PromptLibrary(), JsonMapper.builder().build(), properties.llm());
    }

    @AfterEach
    void tearDown() {
        workspaces.find("run-1").ifPresent(ws -> { });
    }

    private AgentContext context(NodeDefinition node, WorkspaceSession session, List<String> feedback, boolean fallback) {
        Map<String, String> upstream = Map.of("design_doc", "# Design\nUse base62.");
        return new AgentContext("run-1", "Build a URL shortener", Scenario.GREENFIELD, node, 2, feedback,
                name -> Optional.ofNullable(upstream.get(name)), () -> false, session, "demo", fallback);
    }

    private static NodeDefinition node(String id, String agent, List<String> outputs) {
        return new NodeDefinition(id, agent, List.of(), null, outputs, List.of(), List.of(), false, 0, null, 1,
                List.of("src/main/**"), false);
    }

    @Test
    void implementerWritesFilesAndProducesChangeSet() {
        WorkspaceSession session = workspaces.prepare("run-1").session("implement", 2, "agent:ImplementerAgent");
        reply.set("""
                ```json
                {"files": [{"path": "src/main/java/Code.java", "content": "class Code {}"}],
                 "summary": "adds Code",
                 "decisions": [{"title": "Use base62", "rationale": "short URLs", "alternatives": ["hex"]}]}
                ```""");
        AgentContext ctx = context(node("implement", "ImplementerAgent", List.of("change_set")), session,
                List.of("Exit gate failed: compiles: Code.java:1: error: missing semicolon"), false);

        AgentResult result = new ImplementerAgent(support, false).execute(ctx);

        assertThat(result.success()).isTrue();
        assertThat(session.read("src/main/java/Code.java")).contains("class Code {}");
        assertThat(ctx.stagedArtifacts().get("change_set")).contains("\"action\" : \"create\"").contains("+class Code {}");
        assertThat(ctx.decisions()).singleElement().extracting(ProposedDecision::title).isEqualTo("Use base62");
        assertThat(ctx.tokensUsed()).isEqualTo(150);

        LlmRequest request = lastRequest.get();
        assertThat(request.nodeId()).isEqualTo("implement");
        assertThat(request.attemptNo()).isEqualTo(2);
        assertThat(request.recording()).isEqualTo("demo");
        assertThat(request.model()).isEqualTo("test-model");
        assertThat(request.systemPrompt()).contains("Implementer").contains("Spring Boot 4").contains("exactly one JSON object");
        assertThat(request.userPrompt()).contains("# Design").contains("missing semicolon").contains("src/main/java/App.java");
    }

    @Test
    void retryPromptIncludesThePreviousAttemptToRepair() {
        WorkspaceSession session = workspaces.prepare("run-1").session("implement", 2, "agent:x");
        reply.set("{\"files\": [{\"path\": \"src/main/java/A.java\", \"content\": \"class A {}\"}]}");
        AgentContext ctx = new AgentContext("run-1", "req", Scenario.GREENFIELD,
                node("implement", "ImplementerAgent", List.of("change_set")), 2, List.of("A.java:1: error: ';' expected"),
                name -> Optional.empty(), () -> false, session, "demo", false,
                Map.of("src/main/java/A.java", "class A { int x = 1 }"),
                List.of("APPROVED POLICY at security_review by human:lead: accepted risk, deferred"));

        new ImplementerAgent(support, false).execute(ctx);

        assertThat(lastRequest.get().userPrompt()).contains("Previous attempt to repair")
                .contains("class A { int x = 1 }").contains("';' expected")
                .contains("Binding decisions made by humans").contains("accepted risk, deferred");
    }

    @Test
    void fallbackVariantUsesItsOwnPrompt() {
        WorkspaceSession session = workspaces.prepare("run-1").session("implement", 2, "agent:x");
        reply.set("{\"files\": [{\"path\": \"src/main/java/A.java\", \"content\": \"class A {}\"}]}");

        new ImplementerAgent(support, true).execute(context(node("implement", "ImplementerFallbackAgent",
                List.of("change_set")), session, List.of(), true));

        assertThat(lastRequest.get().systemPrompt()).contains("fallback Implementer");
    }

    @Test
    void malformedOrUnsafeAnswersBecomeRetryableFailures() {
        WorkspaceSession session = workspaces.prepare("run-1").session("implement", 1, "agent:x");
        ImplementerAgent agent = new ImplementerAgent(support, false);
        NodeDefinition implement = node("implement", "ImplementerAgent", List.of("change_set"));

        reply.set("Sure! Here is the code...");
        assertThat(agent.execute(context(implement, session, List.of(), false)).summary()).contains("not valid JSON");

        reply.set("{\"files\": []}");
        assertThat(agent.execute(context(implement, session, List.of(), false)).summary()).contains("non-empty");

        reply.set("{\"files\": [{\"path\": \"../../etc/hosts\", \"content\": \"x\"}]}");
        assertThat(agent.execute(context(implement, session, List.of(), false)).summary()).contains("escapes");
    }

    @Test
    void requirementsAgentStoresSpecWithoutDecisions() {
        reply.set("""
                {"summary": "shortener", "changeType": "greenfield",
                 "acceptanceCriteria": [{"id": "AC1", "criterion": "redirects"}],
                 "openQuestions": [], "decisions": [{"title": "t", "rationale": "r"}]}""");
        AgentContext ctx = context(node("requirements", "RequirementsAgent", List.of("requirements_spec")), null,
                List.of(), false);

        assertThat(new RequirementsAgent(support).execute(ctx).success()).isTrue();
        assertThat(ctx.stagedArtifacts().get("requirements_spec")).contains("AC1").doesNotContain("decisions");
    }
}
