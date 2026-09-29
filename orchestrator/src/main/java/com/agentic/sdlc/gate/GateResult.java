package com.agentic.sdlc.gate;

import java.util.Map;

/**
 * PASS: continue. FAIL: the agent can fix it, so retry with {@code message} as feedback.
 * NEEDS_HUMAN: retrying will not help (e.g. the requirement itself is ambiguous), so ask a person.
 * {@code evidence} is optional structured proof (test counts, coverage) kept with the stage's outputs.
 */
public record GateResult(Outcome outcome, String message, Map<String, Object> evidence) {

    public enum Outcome {
        PASS, FAIL, NEEDS_HUMAN
    }

    public static GateResult pass() {
        return new GateResult(Outcome.PASS, null, null);
    }

    public static GateResult fail(String message) {
        return new GateResult(Outcome.FAIL, message, null);
    }

    public static GateResult needsHuman(String message) {
        return new GateResult(Outcome.NEEDS_HUMAN, message, null);
    }

    public GateResult withEvidence(Map<String, Object> evidence) {
        return new GateResult(outcome, message, evidence);
    }
}
