package com.agentic.sdlc.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.agentic.sdlc.agent.AgentResult;
import com.agentic.sdlc.audit.AuditType;
import com.agentic.sdlc.metrics.MetricsService;
import com.agentic.sdlc.metrics.RunMetrics;
import com.agentic.sdlc.support.EngineTestSupport;
import com.agentic.sdlc.support.ScriptedAgent;
import com.agentic.sdlc.state.Artifact;
import com.agentic.sdlc.state.ArtifactRepository;
import com.agentic.sdlc.state.ArtifactStatus;
import com.agentic.sdlc.state.RunStatus;
import com.agentic.sdlc.state.Scenario;
import com.agentic.sdlc.state.StageStatus;

class RetryAndRecoveryTest extends EngineTestSupport {

    @Autowired
    MetricsService metrics;
    @Autowired
    ArtifactRepository artifacts;

    @Test
    void retriesWithFeedbackUntilGatesPass() throws Exception {
        agents.get("Worker").script(ctx -> {
            ctx.writeArtifact("build_out", ctx.attemptNo() == 1 ? "not json" : "{\"ok\": true}");
            return AgentResult.ok("done");
        });

        String runId = startAndWait("retry", Scenario.GREENFIELD);

        assertThat(runStatus(runId)).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(agents.get("Worker").calls()).hasSize(2);
        assertThat(agents.get("Worker").calls().get(1).feedback()).singleElement().asString()
                .contains("validJson");
        assertThat(artifacts.findByRunIdAndNameOrderByVersionAsc(runId, "build_out"))
                .extracting(Artifact::getStatus)
                .containsExactly(ArtifactStatus.DISCARDED, ArtifactStatus.COMMITTED);

        RunMetrics m = metrics.forRun(runId);
        assertThat(m.attempts()).isEqualTo(2);
        assertThat(m.retries()).isEqualTo(1);
        assertThat(m.rollbacks()).isEqualTo(1);
        assertThat(m.mttrMs()).isNotNull();
    }

    @Test
    void hugeFailureMessagesAreTruncatedInsteadOfCrashingTheStage() throws Exception {
        String huge = "x".repeat(50_000);
        agents.get("Worker").script(ctx -> ctx.attemptNo() == 1 ? AgentResult.failed(huge) : ScriptedAgent.writeDefaults(ctx));

        String runId = startAndWait("retry", Scenario.GREENFIELD);

        assertThat(runStatus(runId)).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(agents.get("Worker").calls().get(1).feedback().get(0)).hasSizeLessThanOrEqualTo(12_000)
                .contains("more characters truncated");
    }

    @Test
    void agentExceptionsAreRetriedLikeFailures() throws Exception {
        agents.get("Worker").script(ctx -> {
            if (ctx.attemptNo() == 1) {
                throw new IllegalStateException("LLM timeout");
            }
            return ScriptedAgent.writeDefaults(ctx);
        });

        String runId = startAndWait("retry", Scenario.GREENFIELD);

        assertThat(runStatus(runId)).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(agents.get("Worker").calls().get(1).feedback().get(0)).contains("LLM timeout");
    }

    @Test
    void switchesToFallbackAfterBoundedRetries() throws Exception {
        agents.get("Worker").script(ctx -> AgentResult.failed("cannot do it"));

        String runId = startAndWait("retry", Scenario.GREENFIELD);

        assertThat(runStatus(runId)).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(agents.get("Worker").calls()).hasSize(3); // 1 + maxRetries(2)
        assertThat(agents.get("Backup").calls()).hasSize(1);
        assertThat(agents.get("Backup").calls().get(0).feedback()).hasSize(3);
        assertThat(auditCount(runId, AuditType.FALLBACK_ACTIVATED)).isEqualTo(1);
    }

    @Test
    void escalatesWhenEverythingFailsAndRecoversOnManualRetry() throws Exception {
        AtomicBoolean fixed = new AtomicBoolean(false);
        ScriptedAgent.Behavior failUntilFixed = ctx -> fixed.get()
                ? ScriptedAgent.writeDefaults(ctx) : AgentResult.failed("dependency down");
        agents.get("Worker").script(failUntilFixed);
        agents.get("Backup").script(failUntilFixed);

        String runId = startAndWait("retry", Scenario.GREENFIELD);

        assertThat(runStatus(runId)).isEqualTo(RunStatus.FAILED);
        assertThat(stageStatus(runId, "build")).isEqualTo(StageStatus.FAILED);
        assertThat(stage(runId, "build").getLastError()).contains("All attempts exhausted");
        assertThat(auditCount(runId, AuditType.STAGE_FAILED)).isEqualTo(1);

        fixed.set(true);
        engine.retryStage(runId, "build", "human:ops");
        await(runId);

        assertThat(runStatus(runId)).isEqualTo(RunStatus.SUCCEEDED);
        RunMetrics m = metrics.forRun(runId);
        assertThat(m.failedAttempts()).isEqualTo(4);
        assertThat(m.mttrMs()).isNotNull();
    }

    @Test
    void policyViolationIsDeniedAndNeverCommitted() throws Exception {
        agents.get("Worker").script(ctx -> {
            ctx.writeArtifact("build_out", "{\"apiKey\": \"sk-proj-abcdefghijklmnopqrstuvwxyz123456\"}");
            return AgentResult.ok("done");
        });
        agents.get("Backup").script(ctx -> {
            ctx.writeArtifact("build_out", "{\"apiKey\": \"${OPENAI_API_KEY}\"}");
            return AgentResult.ok("reads the key from configuration");
        });

        String runId = startAndWait("retry", Scenario.GREENFIELD);

        assertThat(runStatus(runId)).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(agents.get("Backup").calls().get(0).feedback().get(0)).contains("secretScan");
        assertThat(artifacts.findByRunIdAndNameOrderByVersionAsc(runId, "build_out"))
                .filteredOn(a -> a.getContent().contains("sk-proj"))
                .extracting(Artifact::getStatus).containsOnly(ArtifactStatus.DISCARDED);
        assertThat(auditCount(runId, AuditType.POLICY_FINDING)).isEqualTo(3);
    }
}
