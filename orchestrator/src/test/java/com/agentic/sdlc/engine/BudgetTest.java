package com.agentic.sdlc.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.agentic.sdlc.agent.AgentResult;
import com.agentic.sdlc.audit.AuditType;
import com.agentic.sdlc.support.EngineTestSupport;
import com.agentic.sdlc.state.RunStatus;
import com.agentic.sdlc.state.Scenario;
import com.agentic.sdlc.state.StageStatus;

@TestPropertySource(properties = "sdlc.budget.max-attempts=2")
class BudgetTest extends EngineTestSupport {

    @Test
    void exceedingTheAttemptBudgetSafeStopsTheRun() throws Exception {
        agents.get("Worker").script(ctx -> AgentResult.failed("still broken"));

        String runId = startAndWait("retry", Scenario.GREENFIELD);

        assertThat(runStatus(runId)).isEqualTo(RunStatus.STOPPED);
        assertThat(run(runId).getStopReason()).startsWith("Budget exceeded");
        assertThat(agents.get("Worker").calls()).hasSize(2);
        assertThat(stageStatus(runId, "build")).isEqualTo(StageStatus.PENDING);
        assertThat(auditCount(runId, AuditType.BUDGET_EXCEEDED)).isEqualTo(1);
    }
}
