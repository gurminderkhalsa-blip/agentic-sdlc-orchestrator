package com.agentic.sdlc.gate;

import java.util.List;

import org.springframework.stereotype.Component;

/** Entry gate: every artifact produced by an ancestor stage is committed and readable. */
@Component
public class InputsAvailableGate implements Gate {

    @Override
    public String name() {
        return "inputsAvailable";
    }

    @Override
    public GateResult check(GateContext context) {
        List<String> missing = context.inputArtifacts().stream()
                .filter(in -> context.artifact(in).isEmpty())
                .toList();
        return missing.isEmpty() ? GateResult.pass() : GateResult.fail("Inputs not available: " + missing);
    }
}
