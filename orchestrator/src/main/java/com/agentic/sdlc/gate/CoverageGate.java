package com.agentic.sdlc.gate;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.agentic.sdlc.config.SdlcProperties;
import com.agentic.sdlc.workspace.Workspace;

/** Exit gate: JaCoCo line coverage from the last test run meets the configured minimum. Put after testsPass. */
@Component
public class CoverageGate implements Gate {

    private final double minimum;

    public CoverageGate(SdlcProperties properties) {
        this.minimum = properties.gates().minLineCoverage();
    }

    @Override
    public String name() {
        return "coverage";
    }

    @Override
    public GateResult check(GateContext context) {
        Workspace workspace = context.workspace().orElse(null);
        if (workspace == null) {
            return GateResult.fail("no workspace");
        }
        double coverage = TestReports.lineCoverage(workspace.root());
        if (coverage < 0) {
            return GateResult.fail("No JaCoCo report found; the testsPass gate must run first");
        }
        Map<String, Object> evidence = Map.of("lineCoverage", Math.round(coverage * 1000) / 1000.0, "minimum", minimum);
        return coverage >= minimum ? GateResult.pass().withEvidence(evidence)
                : GateResult.fail(String.format("Line coverage %.1f%% is below the %.0f%% minimum; test the uncovered paths",
                        coverage * 100, minimum * 100)).withEvidence(evidence);
    }
}
