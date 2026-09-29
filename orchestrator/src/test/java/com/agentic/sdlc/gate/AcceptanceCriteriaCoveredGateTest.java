package com.agentic.sdlc.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agentic.sdlc.support.TestProperties;
import com.agentic.sdlc.workflow.NodeDefinition;
import com.agentic.sdlc.workspace.BuildRunner;
import com.agentic.sdlc.workspace.Workspace;
import com.agentic.sdlc.workspace.WorkspaceService;

import tools.jackson.databind.json.JsonMapper;

class AcceptanceCriteriaCoveredGateTest {

    private static final String SPEC = """
            {"acceptanceCriteria": [{"id": "AC1", "criterion": "a"}, {"id": "AC2", "criterion": "b"}]}""";

    @TempDir
    Path tmp;

    private Workspace workspace;
    private final AcceptanceCriteriaCoveredGate gate = new AcceptanceCriteriaCoveredGate(JsonMapper.builder().build());

    @BeforeEach
    void setUp() throws Exception {
        Path template = Files.createDirectories(tmp.resolve("template/src/test/java"));
        Files.writeString(template.resolve("LinkTest.java"), """
                class LinkTest {
                    @Test void createsLink() {}
                    @Test void recordsClick() {}
                }""");
        workspace = new WorkspaceService(TestProperties.withWorkspace(tmp.resolve("ws"), tmp.resolve("template")),
                new BuildRunner()).prepare("run-ac");
    }

    private GateResult check(String testReport) {
        Map<String, String> artifacts = Map.of("requirements_spec", SPEC, "test_report", testReport);
        NodeDefinition node = new NodeDefinition("tests", "TestAgent", List.of(), null, List.of("test_report"),
                List.of(), List.of(), false, 0, null, 1, List.of("src/test/**"), false);
        return gate.check(new GateContext("run-ac", node, List.of(), name -> Optional.ofNullable(artifacts.get(name)),
                Optional.of(workspace)));
    }

    @Test
    void passesWhenEveryCriterionHasARealTest() {
        GateResult result = check("""
                {"testCases": [{"name": "LinkTest.createsLink", "covers": ["AC1"]},
                               {"name": "recordsClick()", "covers": ["AC2"]}]}""");
        assertThat(result.outcome()).isEqualTo(GateResult.Outcome.PASS);
    }

    @Test
    void failsWhenACriterionHasNoTest() {
        GateResult result = check("""
                {"testCases": [{"name": "createsLink", "covers": ["AC1"]}]}""");
        assertThat(result.outcome()).isEqualTo(GateResult.Outcome.FAIL);
        assertThat(result.message()).contains("[AC2]");
    }

    @Test
    void failsWhenTheReportClaimsATestThatDoesNotExist() {
        GateResult result = check("""
                {"testCases": [{"name": "createsLink", "covers": ["AC1"]},
                               {"name": "analyticsHasThirtyDays", "covers": ["AC2"]}]}""");
        assertThat(result.outcome()).isEqualTo(GateResult.Outcome.FAIL);
        assertThat(result.message()).contains("analyticsHasThirtyDays").contains("[AC2]");
    }
}
