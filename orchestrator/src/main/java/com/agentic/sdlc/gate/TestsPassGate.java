package com.agentic.sdlc.gate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.agentic.sdlc.workspace.BuildResult;
import com.agentic.sdlc.workspace.Workspace;

/**
 * Exit gate: the full test suite runs green and contains at least one test besides the template's.
 * Results from earlier attempts are deleted first, so the verdict and feedback always describe this attempt.
 */
@Component
public class TestsPassGate implements Gate {

    @Override
    public String name() {
        return "testsPass";
    }

    @Override
    public GateResult check(GateContext context) {
        Workspace workspace = context.workspace().orElse(null);
        if (workspace == null) {
            return GateResult.fail("no workspace to test");
        }
        deleteRecursively(workspace.root().resolve("build/test-results"));
        deleteRecursively(workspace.root().resolve("build/reports/jacoco"));
        BuildResult build = workspace.build(List.of("test"));
        if (!build.succeeded() && !build.compileErrors(1).isBlank()) {
            return GateResult.fail(build.failureSummary()).withEvidence(Map.of("compiled", false,
                    "durationMs", build.durationMs()));
        }
        TestReports.TestSummary summary = TestReports.junit(workspace.root());
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("tests", summary.tests());
        evidence.put("failures", summary.failures());
        evidence.put("errors", summary.errors());
        evidence.put("skipped", summary.skipped());
        evidence.put("durationMs", build.durationMs());
        if (!summary.failing().isEmpty()) {
            evidence.put("failing", summary.failing());
        }
        if (!build.succeeded() && summary.tests() == 0) {
            return GateResult.fail(build.failureSummary()).withEvidence(evidence);
        }
        if (summary.failures() + summary.errors() > 0 || !build.succeeded()) {
            return GateResult.fail(summary.failures() + summary.errors() + " of " + summary.tests()
                    + " tests failed:\n" + String.join("\n", summary.failing())).withEvidence(evidence);
        }
        if (summary.tests() < 2) {
            return GateResult.fail("Only " + summary.tests() + " test(s) ran; add tests for the new behaviour")
                    .withEvidence(evidence);
        }
        return GateResult.pass().withEvidence(evidence);
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
