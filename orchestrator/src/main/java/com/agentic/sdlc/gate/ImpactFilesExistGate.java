package com.agentic.sdlc.gate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Exit gate for brownfield impact analysis: every file the analysis says must be modified really exists,
 * which catches hallucinated code paths before design starts.
 */
@Component
public class ImpactFilesExistGate implements Gate {

    private final ObjectMapper json;

    public ImpactFilesExistGate(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public String name() {
        return "impactFilesExist";
    }

    @Override
    public GateResult check(GateContext context) {
        JsonNode report = JsonArtifacts.read(json, context, "impact_report").orElse(null);
        if (report == null) {
            return GateResult.fail("impact_report is missing or not JSON");
        }
        if (context.workspace().isEmpty()) {
            return GateResult.fail("no workspace to check against");
        }
        List<String> files = context.workspace().get().listFiles();
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (JsonNode file : report.path("impactedFiles")) {
            String kind = file.path("changeKind").asString("modify");
            String path = file.path("path").asString("");
            if (!kind.equalsIgnoreCase("create")) {
                checked++;
                if (!files.contains(path)) {
                    missing.add(path);
                }
            }
        }
        if (checked == 0) {
            return GateResult.fail("impact_report names no existing files to modify");
        }
        return missing.isEmpty() ? GateResult.pass().withEvidence(Map.of("verifiedFiles", checked))
                : GateResult.fail("These files do not exist in the repository: " + missing
                        + ". Only reference files from the provided listing.");
    }
}
