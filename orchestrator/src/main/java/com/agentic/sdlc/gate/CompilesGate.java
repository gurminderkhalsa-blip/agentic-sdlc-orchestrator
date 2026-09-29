package com.agentic.sdlc.gate;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.agentic.sdlc.workspace.BuildResult;
import com.agentic.sdlc.workspace.Workspace;

/** Exit gate: main and test sources compile. Compiler errors go back to the agent as feedback. */
@Component
public class CompilesGate implements Gate {

    @Override
    public String name() {
        return "compiles";
    }

    @Override
    public GateResult check(GateContext context) {
        Workspace workspace = context.workspace().orElse(null);
        if (workspace == null) {
            return GateResult.fail("no workspace to build");
        }
        BuildResult build = workspace.build(List.of("compileJava", "compileTestJava"));
        Map<String, Object> evidence = Map.of("tasks", build.tasks(), "exitCode", build.exitCode(),
                "durationMs", build.durationMs());
        return build.succeeded() ? GateResult.pass().withEvidence(evidence)
                : GateResult.fail(build.failureSummary()).withEvidence(evidence);
    }
}
