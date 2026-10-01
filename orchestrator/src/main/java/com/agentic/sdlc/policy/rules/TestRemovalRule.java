package com.agentic.sdlc.policy.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.agentic.sdlc.policy.PolicyFinding;
import com.agentic.sdlc.policy.PolicyRule;
import com.agentic.sdlc.policy.PolicyVerdict;
import com.agentic.sdlc.policy.ProposedChange;
import com.agentic.sdlc.workspace.FileChange;

/**
 * Change control for tests: updating an existing test is allowed (an intended behaviour change may need it),
 * but deleting a test file or reducing its number of test methods needs a human, so a stage cannot make a
 * regression disappear by removing the test that catches it.
 */
@Component
public class TestRemovalRule implements PolicyRule {

    private static final Pattern TEST_METHOD = Pattern.compile("@(Test|ParameterizedTest|RepeatedTest)\\b");

    @Override
    public String name() {
        return "testRemoval";
    }

    @Override
    public List<PolicyFinding> evaluate(ProposedChange change) {
        List<PolicyFinding> findings = new ArrayList<>();
        for (FileChange file : change.files()) {
            if (!file.path().startsWith("src/test/") || !file.existedBefore()) {
                continue;
            }
            int before = count(file.previous());
            int after = file.isDelete() ? 0 : count(file.content());
            if (after < before) {
                findings.add(new PolicyFinding(name(), PolicyVerdict.REQUIRE_APPROVAL, file.path() + ": test methods "
                        + before + " -> " + after + (file.isDelete() ? " (file deleted)" : "")
                        + "; removing existing tests needs human approval"));
            }
        }
        return findings;
    }

    private static int count(String source) {
        Matcher m = TEST_METHOD.matcher(source);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }
}
