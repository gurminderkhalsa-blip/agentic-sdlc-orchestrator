package com.agentic.sdlc.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agentic.sdlc.support.TestProperties;
import com.agentic.sdlc.workflow.NodeDefinition;
import com.agentic.sdlc.workspace.BuildRunner;
import com.agentic.sdlc.workspace.Workspace;
import com.agentic.sdlc.workspace.WorkspaceService;

import tools.jackson.databind.json.JsonMapper;

/** Gates that check the required SDLC artifacts: user stories, diagrams, logging/audit, full review. */
class ArtifactGatesTest {

    @TempDir
    Path tmp;

    private final JsonMapper json = JsonMapper.builder().build();
    private static final NodeDefinition NODE = new NodeDefinition("x", "A", List.of(), null, List.of(), List.of(),
            List.of(), false, 0, null, 1, List.of("src/**"), false);

    private static GateContext context(Map<String, String> artifacts, Workspace ws, String baseline) {
        return new GateContext("run", NODE, List.of(), name -> Optional.ofNullable(artifacts.get(name)),
                Optional.ofNullable(ws), baseline);
    }

    @Test
    void userStoriesMustCoverEveryCriterion() {
        UserStoriesCompleteGate gate = new UserStoriesCompleteGate(json);
        String ok = """
                {"acceptanceCriteria": [{"id": "AC1"}, {"id": "AC2"}],
                 "userStories": [{"id": "US1", "asA": "client", "iWant": "short links", "soThat": "I can share",
                                  "acceptanceCriteria": ["AC1", "AC2"]}]}""";
        String orphan = """
                {"acceptanceCriteria": [{"id": "AC1"}, {"id": "AC2"}],
                 "userStories": [{"id": "US1", "asA": "client", "iWant": "x", "acceptanceCriteria": ["AC1"]}]}""";

        assertThat(gate.check(context(Map.of("requirements_spec", ok), null, null)).outcome()).isEqualTo(GateResult.Outcome.PASS);
        GateResult bad = gate.check(context(Map.of("requirements_spec", orphan), null, null));
        assertThat(bad.outcome()).isEqualTo(GateResult.Outcome.FAIL);
        assertThat(bad.message()).contains("[AC2] belong to no user story").contains("no \"soThat\"");
    }

    @Test
    void designNeedsComponentSequenceAndDataModelDiagrams() {
        DesignDiagramsGate gate = new DesignDiagramsGate();
        String full = "# Design\n```mermaid\nflowchart LR\n A-->B\n```\n```mermaid\nsequenceDiagram\n A->>B: hi\n```\n"
                + "```mermaid\nerDiagram\n LINK ||--o{ CLICK : has\n```\n";
        String partial = "# Design\n```mermaid\nflowchart LR\n A-->B\n```\n";

        assertThat(gate.check(context(Map.of("design_doc", full), null, null)).outcome()).isEqualTo(GateResult.Outcome.PASS);
        assertThat(gate.check(context(Map.of("design_doc", partial), null, null)).message())
                .contains("sequenceDiagram").contains("erDiagram");
    }

    @Test
    void controllersAndServicesMustLogAndAnAuditEntityMustExist() throws Exception {
        Workspace ws = workspace();
        var session = ws.session("implement", 1, "agent:x");
        session.write("src/main/java/demo/LinkController.java", "@RestController class LinkController {}");
        LoggingAndAuditingGate gate = new LoggingAndAuditingGate();

        GateResult missing = gate.check(context(Map.of(), ws, null));
        assertThat(missing.outcome()).isEqualTo(GateResult.Outcome.FAIL);
        assertThat(missing.message()).contains("LinkController.java").contains("no audit trail");

        session.write("src/main/java/demo/LinkController.java", """
                @RestController class LinkController {
                    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LinkController.class);
                }""");
        session.write("src/main/java/demo/AuditEvent.java", "@Entity\npublic class AuditEvent { }");
        assertThat(gate.check(context(Map.of(), ws, null)).outcome()).isEqualTo(GateResult.Outcome.PASS);
    }

    @Test
    void reviewMustListEveryChangedFile() throws Exception {
        Workspace ws = workspace();
        String baseline = ws.headCommit();
        var session = ws.session("implement", 1, "agent:x");
        session.write("src/main/java/demo/A.java", "class A {}");
        session.write("src/test/java/demo/ATest.java", "class ATest {}");
        session.checkpoint("feat(demo): add A");
        AllFilesReviewedGate gate = new AllFilesReviewedGate(json);

        String partial = "{\"filesReviewed\": [{\"path\": \"src/main/java/demo/A.java\", \"verdict\": \"ok\"}]}";
        GateResult bad = gate.check(context(Map.of("review_report", partial), ws, baseline));
        assertThat(bad.outcome()).isEqualTo(GateResult.Outcome.FAIL);
        assertThat(bad.message()).contains("src/test/java/demo/ATest.java");

        String full = "{\"filesReviewed\": [{\"path\": \"src/main/java/demo/A.java\"}, {\"path\": \"src/test/java/demo/ATest.java\"}]}";
        assertThat(gate.check(context(Map.of("review_report", full), ws, baseline)).outcome()).isEqualTo(GateResult.Outcome.PASS);
    }

    private Workspace workspace() throws Exception {
        Files.createDirectories(tmp.resolve("template"));
        Files.writeString(tmp.resolve("template/README.md"), "# t");
        return new WorkspaceService(TestProperties.withWorkspace(tmp.resolve("ws"), tmp.resolve("template")),
                new BuildRunner()).prepare("run-" + System.nanoTime());
    }
}
