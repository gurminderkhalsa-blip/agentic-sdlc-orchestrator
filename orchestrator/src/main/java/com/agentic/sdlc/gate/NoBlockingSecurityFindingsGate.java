package com.agentic.sdlc.gate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Exit gate for the security review. HIGH or CRITICAL findings go to a human: the reviewer cannot fix code,
 * so the decision is to accept the risk or send the implementation back.
 */
@Component
public class NoBlockingSecurityFindingsGate implements Gate {

    private final ObjectMapper json;

    public NoBlockingSecurityFindingsGate(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public String name() {
        return "noBlockingSecurityFindings";
    }

    @Override
    public GateResult check(GateContext context) {
        JsonNode report = JsonArtifacts.read(json, context, "security_report").orElse(null);
        if (report == null) {
            return GateResult.fail("security_report is missing or not JSON");
        }
        List<String> blocking = new ArrayList<>();
        int total = 0;
        for (JsonNode finding : report.path("findings")) {
            total++;
            String severity = finding.path("severity").asString("").toUpperCase();
            if (severity.equals("HIGH") || severity.equals("CRITICAL")) {
                blocking.add(severity + " " + finding.path("file").asString("?") + ": "
                        + finding.path("issue").asString(""));
            }
        }
        Map<String, Object> evidence = Map.of("findings", total, "blocking", blocking.size());
        return blocking.isEmpty() ? GateResult.pass().withEvidence(evidence)
                : GateResult.needsHuman("Security review found blocking issues: " + String.join(" | ", blocking))
                        .withEvidence(evidence);
    }
}
