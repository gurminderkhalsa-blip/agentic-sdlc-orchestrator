package com.agentic.sdlc.policy.rules;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.agentic.sdlc.policy.PolicyFinding;
import com.agentic.sdlc.policy.PolicyRule;
import com.agentic.sdlc.policy.PolicyVerdict;
import com.agentic.sdlc.policy.ProposedChange;
import com.agentic.sdlc.workspace.FileChange;

/**
 * Data-safety guardrail: changing or deleting an existing JPA entity or SQL schema can break stored data,
 * so it needs a human. Creating new entities (greenfield) does not.
 */
@Component
public class SchemaChangeRule implements PolicyRule {

    @Override
    public String name() {
        return "schemaChange";
    }

    @Override
    public List<PolicyFinding> evaluate(ProposedChange change) {
        List<PolicyFinding> findings = new ArrayList<>();
        for (FileChange file : change.files()) {
            boolean schemaFile = file.path().endsWith(".sql") || file.path().startsWith("src/main/resources/db/");
            boolean existingEntity = file.existedBefore() && file.previous().contains("@Entity");
            if (file.existedBefore() && (schemaFile || existingEntity)) {
                findings.add(new PolicyFinding(name(), PolicyVerdict.REQUIRE_APPROVAL,
                        (file.isDelete() ? "deletes" : "modifies") + " persisted data model in " + file.path()));
            }
        }
        return findings;
    }
}
