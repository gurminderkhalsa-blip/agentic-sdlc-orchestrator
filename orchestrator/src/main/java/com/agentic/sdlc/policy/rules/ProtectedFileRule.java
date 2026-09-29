package com.agentic.sdlc.policy.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.agentic.sdlc.policy.PolicyFinding;
import com.agentic.sdlc.policy.PolicyRule;
import com.agentic.sdlc.policy.PolicyVerdict;
import com.agentic.sdlc.policy.ProposedChange;
import com.agentic.sdlc.workspace.FileChange;

/** Change control: build files and runtime configuration may change only with a human's approval. */
@Component
public class ProtectedFileRule implements PolicyRule {

    private static final Set<String> BUILD_FILES = Set.of("build.gradle", "settings.gradle", "gradle.properties");

    @Override
    public String name() {
        return "protectedFile";
    }

    @Override
    public List<PolicyFinding> evaluate(ProposedChange change) {
        List<PolicyFinding> findings = new ArrayList<>();
        for (FileChange file : change.files()) {
            if (BUILD_FILES.contains(file.path())) {
                findings.add(new PolicyFinding(name(), PolicyVerdict.REQUIRE_APPROVAL,
                        file.path() + " changed: dependency or build change needs approval"));
            } else if (file.path().matches("src/main/resources/application.*\\.(yml|yaml|properties)")) {
                findings.add(new PolicyFinding(name(), PolicyVerdict.REQUIRE_APPROVAL,
                        file.path() + " changed: runtime configuration change needs approval"));
            }
        }
        return findings;
    }
}
