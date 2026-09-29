package com.agentic.sdlc.policy.rules;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.agentic.sdlc.config.SdlcProperties;
import com.agentic.sdlc.policy.PolicyFinding;
import com.agentic.sdlc.policy.PolicyRule;
import com.agentic.sdlc.policy.PolicyVerdict;
import com.agentic.sdlc.policy.ProposedChange;

/** Change-control guardrail: oversized outputs are hard to review, so they are rejected and retried smaller. */
@Component
public class ArtifactSizeRule implements PolicyRule {

    private final int maxChars;

    public ArtifactSizeRule(SdlcProperties properties) {
        this.maxChars = properties.policy().maxArtifactChars();
    }

    @Override
    public String name() {
        return "artifactSize";
    }

    @Override
    public List<PolicyFinding> evaluate(ProposedChange change) {
        List<PolicyFinding> findings = new ArrayList<>();
        change.artifacts().forEach((artifact, content) -> {
            if (content.length() > maxChars) {
                findings.add(new PolicyFinding(name(), PolicyVerdict.DENY,
                        artifact + " has " + content.length() + " chars (limit " + maxChars + "); split the change"));
            }
        });
        return findings;
    }
}
