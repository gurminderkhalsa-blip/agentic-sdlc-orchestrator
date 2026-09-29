package com.agentic.sdlc.gate;

import java.util.List;

import org.springframework.stereotype.Component;

/** Exit gate: every output the stage declares was produced and is non-blank. */
@Component
public class ArtifactsPresentGate implements Gate {

    @Override
    public String name() {
        return "artifactsPresent";
    }

    @Override
    public GateResult check(GateContext context) {
        List<String> missing = context.node().outputs().stream()
                .filter(out -> context.artifact(out).map(String::isBlank).orElse(true))
                .toList();
        return missing.isEmpty() ? GateResult.pass() : GateResult.fail("Missing or empty outputs: " + missing);
    }
}
