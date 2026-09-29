package com.agentic.sdlc.support;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.agentic.sdlc.engine.WorkflowEngine;
import com.agentic.sdlc.state.ApprovalRequest;
import com.agentic.sdlc.state.ApprovalRequestRepository;
import com.agentic.sdlc.state.ApprovalStatus;
import com.agentic.sdlc.state.AuditEventRepository;
import com.agentic.sdlc.state.RunStatus;
import com.agentic.sdlc.state.Scenario;
import com.agentic.sdlc.state.StageRun;
import com.agentic.sdlc.state.StageRunRepository;
import com.agentic.sdlc.state.StageStatus;
import com.agentic.sdlc.state.WorkflowRun;
import com.agentic.sdlc.state.WorkflowRunRepository;

/** Spring context with scripted agents, the test workflows and an in-memory database. */
@SpringBootTest(properties = {
        "sdlc.agents.mode=test",
        "sdlc.workflow-locations=classpath:test-workflows/*.yaml",
        "sdlc.retry-backoff=0ms",
        "spring.datasource.url=jdbc:h2:mem:sdlc-${random.uuid};DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "sdlc.workspace.root=build/test-workspaces/${random.uuid}",
        "sdlc.workspace.target-repo=build/test-workspaces/${random.uuid}/target",
        "sdlc.workspace.template=src/test/resources/test-template"
})
@Import(TestAgentsConfig.class)
public abstract class EngineTestSupport {

    protected static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    protected WorkflowEngine engine;
    @Autowired
    protected TestAgentsConfig.TestAgents agents;
    @Autowired
    protected WorkflowRunRepository runs;
    @Autowired
    protected StageRunRepository stages;
    @Autowired
    protected ApprovalRequestRepository approvals;
    @Autowired
    protected AuditEventRepository auditEvents;

    @BeforeEach
    void resetAgents() {
        agents.resetAll();
    }

    protected String startAndWait(String workflow, Scenario scenario) throws Exception {
        WorkflowRun run = engine.start(workflow, "test requirement", scenario, "tester");
        engine.awaitIdle(run.getId(), TIMEOUT);
        return run.getId();
    }

    protected void await(String runId) throws Exception {
        engine.awaitIdle(runId, TIMEOUT);
    }

    protected RunStatus runStatus(String runId) {
        return runs.findById(runId).orElseThrow().getStatus();
    }

    protected WorkflowRun run(String runId) {
        return runs.findById(runId).orElseThrow();
    }

    protected StageRun stage(String runId, String nodeId) {
        return stages.findByRunIdAndNodeId(runId, nodeId).orElseThrow();
    }

    protected StageStatus stageStatus(String runId, String nodeId) {
        return stage(runId, nodeId).getStatus();
    }

    protected ApprovalRequest pendingApproval(String runId) {
        return approvals.findByRunIdOrderByIdAsc(runId).stream()
                .filter(a -> a.getStatus() == ApprovalStatus.PENDING)
                .findFirst().orElseThrow(() -> new AssertionError("no pending approval in run " + runId));
    }

    protected long auditCount(String runId, String type) {
        return auditEvents.countByRunIdAndType(runId, type);
    }
}
