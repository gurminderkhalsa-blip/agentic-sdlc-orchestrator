package com.agentic.sdlc.gate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.agentic.sdlc.workspace.Workspace;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Exit gate for the test stage: every acceptance criterion of the approved spec is claimed by at least one
 * test case, and every claimed test case really exists as a test method in src/test. Line coverage alone
 * let a stage pass while an entire feature had no tests; this ties tests back to the requirements.
 */
@Component
public class AcceptanceCriteriaCoveredGate implements Gate {

    private final ObjectMapper json;

    public AcceptanceCriteriaCoveredGate(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public String name() {
        return "acceptanceCriteriaCovered";
    }

    @Override
    public GateResult check(GateContext context) {
        JsonNode spec = JsonArtifacts.read(json, context, "requirements_spec").orElse(null);
        JsonNode report = JsonArtifacts.read(json, context, "test_report").orElse(null);
        Workspace workspace = context.workspace().orElse(null);
        if (spec == null || report == null || workspace == null) {
            return GateResult.fail("requirements_spec, test_report and a workspace are required");
        }

        String testSources = testSources(workspace);
        Map<String, List<String>> coveredBy = new LinkedHashMap<>();
        spec.path("acceptanceCriteria").forEach(ac -> coveredBy.put(ac.path("id").asString(""), new ArrayList<>()));
        List<String> phantom = new ArrayList<>();
        for (JsonNode testCase : report.path("testCases")) {
            String name = testCase.path("name").asString("");
            String method = name.substring(name.lastIndexOf('.') + 1).replaceAll("\\(.*", "").trim();
            if (method.isEmpty() || !Pattern.compile("\\b" + Pattern.quote(method) + "\\s*\\(").matcher(testSources).find()) {
                phantom.add(name);
                continue;
            }
            testCase.path("covers").forEach(ac -> coveredBy.computeIfPresent(ac.asString(), (k, v) -> {
                v.add(method);
                return v;
            }));
        }

        Set<String> uncovered = new LinkedHashSet<>();
        coveredBy.forEach((ac, tests) -> {
            if (tests.isEmpty()) {
                uncovered.add(ac);
            }
        });
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("criteria", coveredBy.size());
        evidence.put("uncovered", List.copyOf(uncovered));
        evidence.put("phantomTestCases", phantom);

        List<String> problems = new ArrayList<>();
        if (!uncovered.isEmpty()) {
            problems.add("No test covers acceptance criteria " + uncovered + ". Write tests for them and list them in "
                    + "testCases with the criterion ids in \"covers\" (existing tests may be listed too).");
        }
        if (!phantom.isEmpty()) {
            problems.add("testCases names tests that do not exist in src/test: " + phantom
                    + ". Only list test methods that are in the files.");
        }
        return problems.isEmpty() ? GateResult.pass().withEvidence(evidence)
                : GateResult.fail(String.join(" ", problems)).withEvidence(evidence);
    }

    private static String testSources(Workspace workspace) {
        StringBuilder all = new StringBuilder();
        for (String path : workspace.listFiles()) {
            if (path.startsWith("src/test/") && path.endsWith(".java")) {
                workspace.read(path).ifPresent(content -> all.append(content).append('\n'));
            }
        }
        return all.toString();
    }
}
