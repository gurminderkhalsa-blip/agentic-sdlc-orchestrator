package com.agentic.sdlc.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.agentic.sdlc.agent.AgentResult;
import com.agentic.sdlc.audit.AuditType;
import com.agentic.sdlc.support.EngineTestSupport;
import com.agentic.sdlc.support.ScriptedAgent;
import com.agentic.sdlc.state.RunStatus;
import com.agentic.sdlc.state.Scenario;
import com.agentic.sdlc.state.StageStatus;
import com.agentic.sdlc.workspace.Workspace;
import com.agentic.sdlc.workspace.WorkspaceService;

/** Stages that change code: checkpoints, file rollback, git revert on rejection, and publish on release. */
class WorkspaceWorkflowTest extends EngineTestSupport {

    @Autowired
    WorkspaceService workspaces;

    private void workerWritesCode() {
        agents.get("Worker").script(ctx -> {
            var ws = ctx.workspace().orElseThrow();
            if (ctx.attemptNo() == 1) {
                ws.write("README.md", "outside the stage's scope");   // denied by the writeScope policy
            }
            ws.write("src/main/java/demo/Service.java", "class Service { int v = " + ctx.attemptNo() + "; }");
            return ScriptedAgent.writeDefaults(ctx);
        });
    }

    @Test
    void deniedAttemptIsRolledBackAndPassingAttemptIsCheckpointedThenPublished() throws Exception {
        workerWritesCode();
        String runId = startAndWait("coded", Scenario.GREENFIELD);
        Workspace ws = workspaces.find(runId).orElseThrow();

        assertThat(agents.get("Worker").calls()).hasSize(2);
        assertThat(agents.get("Worker").calls().get(1).feedback().get(0)).contains("writeScope");
        assertThat(agents.get("Worker").calls().get(1).previousAttemptFiles())
                .containsEntry("src/main/java/demo/Service.java", "class Service { int v = 1; }")
                .containsKey("README.md");
        assertThat(ws.read("README.md")).hasValue("# demo\n");   // attempt 1's write was restored
        assertThat(ws.readAtHead("src/main/java/demo/Service.java")).contains("class Service { int v = 2; }");
        assertThat(ws.history(5).get(0)).contains("[build] attempt 2");
        assertThat(auditCount(runId, AuditType.CHECKPOINT)).isEqualTo(1);

        assertThat(runStatus(runId)).isEqualTo(RunStatus.AWAITING_APPROVAL);
        engine.approve(pendingApproval(runId).getId(), "human:release-manager", "ship it");
        await(runId);

        assertThat(runStatus(runId)).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run(runId).getPublishedCommit()).isEqualTo(ws.headCommit()).isEqualTo(workspaces.targetHead());
        assertThat(auditCount(runId, AuditType.RELEASE_PUBLISHED)).isEqualTo(1);
    }

    @Test
    void sendingCodeStageBackRevertsItsCommitAndReplansDownstream() throws Exception {
        agents.get("Worker").script(ctx -> {
            String version = ctx.feedback().isEmpty() ? "v1" : "v2";
            ctx.workspace().orElseThrow().write("src/main/java/demo/Service.java", "class Service { String v = \"" + version + "\"; }");
            return ScriptedAgent.writeDefaults(ctx);
        });
        String runId = startAndWait("coded", Scenario.GREENFIELD);
        assertThat(stageStatus(runId, "ship")).isEqualTo(StageStatus.AWAITING_APPROVAL);

        engine.rerunStage(runId, "build", "human:lead", "use v2");
        await(runId);

        Workspace ws = workspaces.find(runId).orElseThrow();
        assertThat(ws.readAtHead("src/main/java/demo/Service.java").orElseThrow()).contains("\"v2\"");
        assertThat(agents.get("Worker").calls().get(1).previousAttemptFiles())
                .containsEntry("src/main/java/demo/Service.java", "class Service { String v = \"v1\"; }");
        assertThat(ws.history(10)).anySatisfy(line -> assertThat(line).contains("Revert"));
        assertThat(agents.get("Builder").calls()).hasSize(2);
        assertThat(stageStatus(runId, "ship")).isEqualTo(StageStatus.AWAITING_APPROVAL);
        assertThat(approvals.findByRunIdOrderByIdAsc(runId)).extracting(a -> a.getStatus().name())
                .containsExactly("CANCELLED", "PENDING");
    }

    @Test
    void repairContextSurvivesARerunOfTheStage() throws Exception {
        agents.get("Worker").script(ctx -> {
            ctx.workspace().orElseThrow().write("src/main/java/demo/Draft.java", "class Draft { /* attempt " + ctx.attemptNo() + " */ }");
            return AgentResult.failed("not good enough");
        });
        String runId = startAndWait("coded", Scenario.GREENFIELD);
        assertThat(stageStatus(runId, "build")).isEqualTo(StageStatus.FAILED);

        agents.get("Worker").script(ScriptedAgent::writeDefaults);
        engine.rerunStage(runId, "build", "human:lead", null);
        await(runId);

        assertThat(agents.get("Worker").calls().get(3).previousAttemptFiles())
                .containsEntry("src/main/java/demo/Draft.java", "class Draft { /* attempt 3 */ }");
    }

    @Test
    void partialRepairAttemptsDoNotShrinkTheRepairBase() throws Exception {
        agents.get("Worker").script(ctx -> {
            var ws = ctx.workspace().orElseThrow();
            if (ctx.attemptNo() == 1) {
                ws.write("src/main/java/demo/A.java", "class A {}");
                ws.write("src/main/java/demo/B.java", "class B {}");
            } else {
                ws.write("src/main/java/demo/B.java", "class B { int fixed; }");
            }
            return ctx.attemptNo() < 3 ? AgentResult.failed("still wrong") : ScriptedAgent.writeDefaults(ctx);
        });
        startAndWait("coded", Scenario.GREENFIELD);

        assertThat(agents.get("Worker").calls().get(2).previousAttemptFiles())
                .containsEntry("src/main/java/demo/A.java", "class A {}")
                .containsEntry("src/main/java/demo/B.java", "class B { int fixed; }");
    }

    @Test
    void reworkKeepsFilesTheAgentDidNotReturnAgain() throws Exception {
        agents.get("Worker").script(ctx -> {
            var ws = ctx.workspace().orElseThrow();
            if (ctx.feedback().isEmpty()) {
                ws.write("src/main/java/demo/Feature.java", "class Feature {}");
                ws.write("src/main/java/demo/Query.java", "class Query { String sql = \"local\"; }");
            } else {
                ws.write("src/main/java/demo/Query.java", "class Query { String sql = \"utc\"; }");
            }
            return ScriptedAgent.writeDefaults(ctx);
        });
        String runId = startAndWait("coded", Scenario.GREENFIELD);

        engine.rerunStage(runId, "build", "human:lead", "group by UTC");
        await(runId);

        Workspace ws = workspaces.find(runId).orElseThrow();
        assertThat(ws.readAtHead("src/main/java/demo/Query.java").orElseThrow()).contains("utc");
        assertThat(ws.readAtHead("src/main/java/demo/Feature.java")).hasValue("class Feature {}");
    }

    @Test
    void agentFailureRollsBackItsFiles() throws Exception {
        agents.get("Worker").script(ctx -> {
            ctx.workspace().orElseThrow().write("src/main/java/demo/Half.java", "class Half {");
            return ctx.attemptNo() < 3 ? AgentResult.failed("gave up") : ScriptedAgent.writeDefaults(ctx);
        });
        String runId = startAndWait("coded", Scenario.GREENFIELD);

        Workspace ws = workspaces.find(runId).orElseThrow();
        assertThat(auditCount(runId, AuditType.ROLLBACK)).isEqualTo(2);
        assertThat(ws.readAtHead("src/main/java/demo/Half.java")).contains("class Half {");
    }
}
