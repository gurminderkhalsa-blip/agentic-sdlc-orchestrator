package com.agentic.sdlc.policy;

import java.util.Comparator;
import java.util.List;

public record PolicyDecision(PolicyVerdict verdict, List<PolicyFinding> findings) {

    static PolicyDecision of(List<PolicyFinding> findings) {
        PolicyVerdict worst = findings.stream().map(PolicyFinding::verdict)
                .max(Comparator.naturalOrder()).orElse(PolicyVerdict.ALLOW);
        return new PolicyDecision(worst, List.copyOf(findings));
    }

    public String describe(PolicyVerdict level) {
        return String.join("; ", findings.stream().filter(f -> f.verdict() == level)
                .map(f -> f.rule() + ": " + f.message()).toList());
    }
}
