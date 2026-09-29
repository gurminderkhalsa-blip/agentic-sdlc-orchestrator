package com.agentic.sdlc.policy.rules;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.agentic.sdlc.policy.PolicyFinding;
import com.agentic.sdlc.policy.PolicyRule;
import com.agentic.sdlc.policy.PolicyVerdict;
import com.agentic.sdlc.policy.ProposedChange;
import com.agentic.sdlc.workspace.FileChange;

/**
 * Change control: a stage may only change the paths its workflow node declares in {@code writes}
 * (e.g. the test stage may touch src/test/** but not production code).
 */
@Component
public class WriteScopeRule implements PolicyRule {

    @Override
    public String name() {
        return "writeScope";
    }

    @Override
    public List<PolicyFinding> evaluate(ProposedChange change) {
        List<PathMatcher> allowed = change.writeScopes().stream()
                .map(glob -> FileSystems.getDefault().getPathMatcher("glob:" + glob)).toList();
        List<PolicyFinding> findings = new ArrayList<>();
        for (FileChange file : change.files()) {
            Path path = Path.of(file.path());
            if (allowed.stream().noneMatch(m -> m.matches(path))) {
                findings.add(new PolicyFinding(name(), PolicyVerdict.DENY, "stage " + change.nodeId()
                        + " may not change " + file.path() + " (allowed: " + change.writeScopes() + ")"));
            }
        }
        return findings;
    }
}
