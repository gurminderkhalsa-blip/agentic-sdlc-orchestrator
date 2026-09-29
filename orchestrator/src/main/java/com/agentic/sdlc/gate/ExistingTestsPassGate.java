package com.agentic.sdlc.gate;

import org.springframework.stereotype.Component;

/**
 * Regression gate for code-changing stages: the tests already in the repository must still pass, which also
 * proves the application context still starts. It catches a broken change at implementation time instead of
 * leaving it for the test stage, which is not allowed to fix production code.
 */
@Component
public class ExistingTestsPassGate implements Gate {

    @Override
    public String name() {
        return "existingTestsPass";
    }

    @Override
    public GateResult check(GateContext context) {
        GateResult result = TestsPassGate.runTests(context, 0);
        return result.outcome() == GateResult.Outcome.PASS ? result
                : GateResult.fail("Existing tests no longer pass (regression or the app no longer starts). "
                        + result.message()).withEvidence(result.evidence());
    }
}
