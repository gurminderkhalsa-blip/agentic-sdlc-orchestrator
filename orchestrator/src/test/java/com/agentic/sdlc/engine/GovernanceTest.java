package com.agentic.sdlc.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.agentic.sdlc.agent.AgentResult;
import com.agentic.sdlc.audit.AuditType;
import com.agentic.sdlc.common.ConflictException;
import com.agentic.sdlc.support.EngineTestSupport;
import com.agentic.sdlc.state.ApprovalReason;
import com.agentic.sdlc.state.ApprovalRequest;
import com.agentic.sdlc.state.RunStatus;
import com.agentic.sdlc.state.Scenario;
import com.agentic.sdlc.state.StageStatus;

/** Human checkpoints, conditional stages and dynamic re-planning. */
class GovernanceTest extends EngineTestSupport {

    private static final String GREENFIELD_SPEC = "{\"changeType\": \"greenfield\", \"openQuestions\": []}";
    private static final String BROWNFIELD_SPEC = "{\"changeType\": \"brownfield\", \"openQuestions\": []}";

    private void specWrites(String spec) {
        agents.get("Spec").script(ctx -> {
            ctx.writeArtifact("requirements_spec", spec);
            return AgentResult.ok("spec");
        });
    }

    @Test
    void pausesAtCheckpointUntilApproved() throws Exception {
        specWrites(GREENFIELD_SPEC);
        String runId = startAndWait("governed", Scenario.GREENFIELD);

        assertThat(runStatus(runId)).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(stageStatus(runId, "plan")).isEqualTo(StageStatus.PENDING);
        assertThat(agents.get("Planner").calls()).isEmpty();
        ApprovalRequest approval = pendingApproval(runId);
        assertThat(approval.getReason()).isEqualTo(ApprovalReason.STAGE_CHECKPOINT);

        engine.approve(approval.getId(), "human:lead", "looks right");
        await(runId);

        assertThat(runStatus(runId)).isEqualTo(RunStatus.SUCCEEDED);
        assertThatThrownBy(() -> engine.approve(approval.getId(), "human:lead", null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void rejectionReRunsStageWithReviewerFeedback() throws Exception {
        specWrites(GREENFIELD_SPEC);
        String runId = startAndWait("governed", Scenario.GREENFIELD);

        engine.reject(pendingApproval(runId).getId(), "human:lead", "add rate limiting");
        await(runId);

        assertThat(agents.get("Spec").calls()).hasSize(2);
        assertThat(agents.get("Spec").calls().get(1).feedback()).containsExactly("Human reviewer: add rate limiting");
        assertThat(runStatus(runId)).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(auditCount(runId, AuditType.ROLLBACK)).isEqualTo(1);
    }

    @Test
    void skipsConditionalStageForGreenfieldAndRunsItForBrownfield() throws Exception {
        specWrites(GREENFIELD_SPEC);
        String greenfield = startAndWait("governed", Scenario.GREENFIELD);
        engine.approve(pendingApproval(greenfield).getId(), "human:lead", null);
        await(greenfield);

        assertThat(runStatus(greenfield)).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(stageStatus(greenfield, "impact")).isEqualTo(StageStatus.SKIPPED);

        specWrites(BROWNFIELD_SPEC);
        String brownfield = startAndWait("governed", Scenario.GREENFIELD);
        engine.approve(pendingApproval(brownfield).getId(), "human:lead", null);
        await(brownfield);

        assertThat(stageStatus(brownfield, "impact")).isEqualTo(StageStatus.SUCCEEDED);
    }

    @Test
    void ambiguousRequirementGoesToHumanInsteadOfRetrying() throws Exception {
        agents.get("Spec").script(ctx -> {
            boolean answered = ctx.feedback().stream().anyMatch(f -> f.contains("expire after 30 days"));
            ctx.writeArtifact("requirements_spec", answered
                    ? "{\"openQuestions\": [{\"question\": \"Should links expire?\", \"answer\": \"30 days\"}]}"
                    : "{\"openQuestions\": [{\"question\": \"Should links expire?\"}]}");
            return AgentResult.ok("spec");
        });
        String runId = startAndWait("governed", Scenario.AMBIGUOUS);

        ApprovalRequest clarification = pendingApproval(runId);
        assertThat(clarification.getReason()).isEqualTo(ApprovalReason.CLARIFICATION);
        assertThat(clarification.getSummary()).contains("Should links expire?");
        assertThat(agents.get("Spec").calls()).hasSize(1);

        engine.reject(clarification.getId(), "human:po", "Links expire after 30 days");
        await(runId);

        assertThat(pendingApproval(runId).getReason()).isEqualTo(ApprovalReason.STAGE_CHECKPOINT);
    }

    @Test
    void revisingUpstreamArtifactReplansOnlyDependentStages() throws Exception {
        String runId = startAndWait("diamond", Scenario.GREENFIELD);
        assertThat(runStatus(runId)).isEqualTo(RunStatus.SUCCEEDED);

        engine.reviseArtifact(runId, "b_out", "{\"edited\": true}", "human:architect", "tighten interface");
        await(runId);

        assertThat(runStatus(runId)).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(agents.get("Delta").calls()).hasSize(2);
        assertThat(agents.get("Gamma").calls()).hasSize(1);
        assertThat(agents.get("Alpha").calls()).hasSize(1);
        assertThat(agents.get("Delta").calls().get(1).readArtifact("b_out")).contains("{\"edited\": true}");
        assertThat(auditCount(runId, AuditType.STAGE_INVALIDATED)).isEqualTo(1);
    }

    @Test
    void revisedSpecReEvaluatesConditionalStage() throws Exception {
        specWrites(GREENFIELD_SPEC);
        String runId = startAndWait("governed", Scenario.GREENFIELD);
        engine.approve(pendingApproval(runId).getId(), "human:lead", null);
        await(runId);
        assertThat(stageStatus(runId, "impact")).isEqualTo(StageStatus.SKIPPED);

        engine.reviseArtifact(runId, "requirements_spec", BROWNFIELD_SPEC, "human:lead",
                "this changes the existing service");
        await(runId);

        assertThat(stageStatus(runId, "impact")).isEqualTo(StageStatus.SUCCEEDED);
        assertThat(agents.get("Builder").calls()).hasSize(2);
        assertThat(runStatus(runId)).isEqualTo(RunStatus.SUCCEEDED);
    }
}
