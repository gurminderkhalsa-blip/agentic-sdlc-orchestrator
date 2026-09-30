package com.agentic.sdlc.gate;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Exit gate for the review stage: every source and build file this run changed appears in the review's
 * filesReviewed list, so "all submitted code was reviewed" is checked, not claimed.
 */
@Component
public class AllFilesReviewedGate implements Gate {

    private final ObjectMapper json;

    public AllFilesReviewedGate(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public String name() {
        return "allFilesReviewed";
    }

    @Override
    public GateResult check(GateContext context) {
        JsonNode review = JsonArtifacts.read(json, context, "review_report").orElse(null);
        if (review == null) {
            return GateResult.fail("review_report is missing or not JSON");
        }
        if (context.workspace().isEmpty() || context.baselineCommit() == null) {
            return GateResult.fail("no workspace or baseline to compare against");
        }
        List<String> changed = context.workspace().get().changedFilesSince(context.baselineCommit()).stream()
                .filter(p -> p.startsWith("src/") || p.equals("build.gradle"))
                .toList();
        Set<String> reviewed = new HashSet<>();
        review.path("filesReviewed").forEach(f -> reviewed.add(f.path("path").asString("")));
        List<String> missing = changed.stream().filter(p -> !reviewed.contains(p)).toList();
        Map<String, Object> evidence = Map.of("changedFiles", changed.size(), "reviewedFiles", reviewed.size(),
                "notReviewed", missing);
        return missing.isEmpty() ? GateResult.pass().withEvidence(evidence)
                : GateResult.fail("filesReviewed must include every changed file; missing: " + missing)
                        .withEvidence(evidence);
    }
}
