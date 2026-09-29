package com.agentic.sdlc.policy.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.agentic.sdlc.policy.PolicyFinding;
import com.agentic.sdlc.policy.PolicyRule;
import com.agentic.sdlc.policy.PolicyVerdict;
import com.agentic.sdlc.policy.ProposedChange;

/** Security guardrail: blocks any artifact or source file that looks like it contains a credential. */
@Component
public class SecretScanRule implements PolicyRule {

    private static final Map<String, Pattern> PATTERNS = Map.of(
            "OpenAI API key", Pattern.compile("sk-(proj-)?[A-Za-z0-9_-]{20,}"),
            "AWS access key", Pattern.compile("AKIA[0-9A-Z]{16}"),
            "private key", Pattern.compile("-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----"),
            "hard-coded password", Pattern.compile("(?i)(password|passwd|secret)\\s*[=:]\\s*[\"'][^\"'\\s]{6,}[\"']"),
            "GitHub token", Pattern.compile("gh[pousr]_[A-Za-z0-9]{36}"));

    @Override
    public String name() {
        return "secretScan";
    }

    @Override
    public List<PolicyFinding> evaluate(ProposedChange change) {
        List<PolicyFinding> findings = new ArrayList<>();
        change.artifacts().forEach((artifact, content) -> scan(artifact, content, findings));
        change.files().stream().filter(f -> !f.isDelete()).forEach(f -> scan(f.path(), f.content(), findings));
        return findings;
    }

    private void scan(String where, String content, List<PolicyFinding> findings) {
        PATTERNS.forEach((label, pattern) -> {
            if (pattern.matcher(content).find()) {
                findings.add(new PolicyFinding(name(), PolicyVerdict.DENY,
                        "possible " + label + " in " + where + "; use configuration or a secret store"));
            }
        });
    }
}
