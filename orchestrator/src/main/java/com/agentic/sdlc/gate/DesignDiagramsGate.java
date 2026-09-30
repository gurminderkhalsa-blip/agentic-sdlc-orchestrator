package com.agentic.sdlc.gate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Exit gate for the design stage: the design document contains Mermaid diagrams for the components, at least
 * one request sequence, and the data model, so the design can be reviewed visually.
 */
@Component
public class DesignDiagramsGate implements Gate {

    private static final Pattern MERMAID_BLOCK = Pattern.compile("```mermaid\\s*\\n(.*?)```", Pattern.DOTALL);
    private static final Map<String, Pattern> REQUIRED = new LinkedHashMap<>();

    static {
        REQUIRED.put("component diagram (flowchart/graph)", Pattern.compile("^\\s*(flowchart|graph)\\b"));
        REQUIRED.put("sequenceDiagram", Pattern.compile("^\\s*sequenceDiagram\\b"));
        REQUIRED.put("erDiagram of the data model", Pattern.compile("^\\s*(erDiagram|classDiagram)\\b"));
    }

    @Override
    public String name() {
        return "designDiagrams";
    }

    @Override
    public GateResult check(GateContext context) {
        String design = context.artifact("design_doc").orElse("");
        List<String> diagrams = new ArrayList<>();
        Matcher m = MERMAID_BLOCK.matcher(design);
        while (m.find()) {
            diagrams.add(m.group(1));
        }
        List<String> missing = new ArrayList<>();
        REQUIRED.forEach((label, pattern) -> {
            if (diagrams.stream().noneMatch(d -> pattern.matcher(d).find())) {
                missing.add(label);
            }
        });
        Map<String, Object> evidence = Map.of("mermaidDiagrams", diagrams.size());
        return missing.isEmpty() ? GateResult.pass().withEvidence(evidence)
                : GateResult.fail("design_doc is missing Mermaid diagrams: " + missing
                        + ". Add them as ```mermaid fenced blocks.").withEvidence(evidence);
    }
}
