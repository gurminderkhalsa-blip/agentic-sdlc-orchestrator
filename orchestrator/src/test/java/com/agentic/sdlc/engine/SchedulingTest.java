package com.agentic.sdlc.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.agentic.sdlc.support.EngineTestSupport;
import com.agentic.sdlc.support.ScriptedAgent;
import com.agentic.sdlc.state.RunStatus;
import com.agentic.sdlc.state.Scenario;
import com.agentic.sdlc.state.StageRun;
import com.agentic.sdlc.state.StageStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class SchedulingTest extends EngineTestSupport {

    @Autowired
    ObjectMapper json;

    @Test
    void runsDiamondToCompletion() throws Exception {
        String runId = startAndWait("diamond", Scenario.GREENFIELD);

        assertThat(runStatus(runId)).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(stages.findByRunIdOrderByIdAsc(runId)).extracting(StageRun::getStatus)
                .containsOnly(StageStatus.SUCCEEDED);
        assertThat(run(runId).getEndedAt()).isNotNull();
    }

    @Test
    void parallelBranchesRunConcurrentlyAndJoinWaitsForBoth() throws Exception {
        // Both b and c must be inside their agents at the same time, or the barrier times out.
        CyclicBarrier bothRunning = new CyclicBarrier(2);
        ScriptedAgent.Behavior meetThenWrite = ctx -> {
            bothRunning.await(5, TimeUnit.SECONDS);
            return ScriptedAgent.writeDefaults(ctx);
        };
        agents.get("Beta").script(meetThenWrite);
        agents.get("Gamma").script(meetThenWrite);

        String runId = startAndWait("diamond", Scenario.GREENFIELD);

        assertThat(runStatus(runId)).isEqualTo(RunStatus.SUCCEEDED);
        StageRun b = stage(runId, "b");
        StageRun c = stage(runId, "c");
        StageRun d = stage(runId, "d");
        assertThat(d.getStartedAt()).isAfterOrEqualTo(b.getEndedAt()).isAfterOrEqualTo(c.getEndedAt());
    }

    @Test
    void recordsLineageOfAllUpstreamInputs() throws Exception {
        String runId = startAndWait("diamond", Scenario.GREENFIELD);

        JsonNode lineage = json.readTree(stage(runId, "d").getInputHashes());
        assertThat(lineage.propertyNames()).containsExactlyInAnyOrder("a_out", "b_out", "c_out");
        assertThat(agents.get("Delta").calls().get(0).readArtifact("a_out")).isPresent();
    }
}
