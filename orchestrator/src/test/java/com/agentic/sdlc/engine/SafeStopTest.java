package com.agentic.sdlc.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.agentic.sdlc.audit.AuditType;
import com.agentic.sdlc.support.EngineTestSupport;
import com.agentic.sdlc.support.ScriptedAgent;
import com.agentic.sdlc.state.AttemptStatus;
import com.agentic.sdlc.state.RunStatus;
import com.agentic.sdlc.state.Scenario;
import com.agentic.sdlc.state.StageAttempt;
import com.agentic.sdlc.state.StageAttemptRepository;
import com.agentic.sdlc.state.StageStatus;
import com.agentic.sdlc.state.WorkflowRun;

class SafeStopTest extends EngineTestSupport {

    @Autowired
    StageAttemptRepository attempts;

    @Test
    void stopInterruptsRunningAgentAndResumeFinishesTheRun() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        agents.get("Worker").script(ctx -> {
            if (ctx.attemptNo() == 1) {
                started.countDown();
                while (true) {
                    ctx.checkNotStopped();
                    Thread.sleep(5);
                }
            }
            return ScriptedAgent.writeDefaults(ctx);
        });
        WorkflowRun run = engine.start("retry", "req", Scenario.GREENFIELD, "tester");
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        engine.stop(run.getId(), "human:ops", "suspicious output");
        await(run.getId());

        assertThat(runStatus(run.getId())).isEqualTo(RunStatus.STOPPED);
        assertThat(stageStatus(run.getId(), "build")).isEqualTo(StageStatus.PENDING);
        assertThat(attempts.findByRunIdOrderByIdAsc(run.getId())).extracting(StageAttempt::getStatus)
                .containsExactly(AttemptStatus.ABORTED);

        engine.resume(run.getId(), "human:ops");
        await(run.getId());

        assertThat(runStatus(run.getId())).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(auditCount(run.getId(), AuditType.RUN_RESUMED)).isEqualTo(1);
    }
}
