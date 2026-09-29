package com.agentic.sdlc.policy;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/** Runs every registered rule; the most severe finding decides (DENY beats REQUIRE_APPROVAL beats ALLOW). */
@Component
public class PolicyEngine {

    private final List<PolicyRule> rules;

    public PolicyEngine(List<PolicyRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public PolicyDecision evaluate(ProposedChange change) {
        List<PolicyFinding> findings = new ArrayList<>();
        for (PolicyRule rule : rules) {
            findings.addAll(rule.evaluate(change));
        }
        return PolicyDecision.of(findings);
    }
}
