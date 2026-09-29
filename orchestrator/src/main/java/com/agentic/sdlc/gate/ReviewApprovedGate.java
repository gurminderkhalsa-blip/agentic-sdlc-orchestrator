package com.agentic.sdlc.gate;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exit gate: the reviewer agent approves. "changes_requested" goes to a human with the reviewer's reasons. */
@Component
public class ReviewApprovedGate implements Gate {

    private final ObjectMapper json;

    public ReviewApprovedGate(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public String name() {
        return "reviewApproved";
    }

    @Override
    public GateResult check(GateContext context) {
        JsonNode review = JsonArtifacts.read(json, context, "review_report").orElse(null);
        if (review == null) {
            return GateResult.fail("review_report is missing or not JSON");
        }
        String verdict = review.path("verdict").asString("");
        if (verdict.equalsIgnoreCase("approve")) {
            return GateResult.pass();
        }
        if (!verdict.equalsIgnoreCase("changes_requested")) {
            return GateResult.fail("verdict must be 'approve' or 'changes_requested', not '" + verdict + "'");
        }
        List<String> reasons = new ArrayList<>();
        review.path("findings").forEach(f -> reasons.add(f.path("summary").asString(f.toString())));
        return GateResult.needsHuman("Reviewer requested changes: " + String.join(" | ", reasons));
    }
}
