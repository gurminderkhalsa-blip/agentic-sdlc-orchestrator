package com.agentic.sdlc.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.agentic.sdlc.audit.AuditService;
import com.agentic.sdlc.audit.AuditType;
import com.agentic.sdlc.common.ConflictException;
import com.agentic.sdlc.common.Hashing;
import com.agentic.sdlc.common.NotFoundException;
import com.agentic.sdlc.common.Text;
import com.agentic.sdlc.condition.ConditionContext;
import com.agentic.sdlc.condition.ConditionRegistry;
import com.agentic.sdlc.state.ApprovalRequest;
import com.agentic.sdlc.state.ApprovalRequestRepository;
import com.agentic.sdlc.state.ApprovalStatus;
import com.agentic.sdlc.state.Artifact;
import com.agentic.sdlc.state.AttemptStatus;
import com.agentic.sdlc.state.Decision;
import com.agentic.sdlc.state.DecisionRepository;
import com.agentic.sdlc.state.RunStatus;
import com.agentic.sdlc.state.Scenario;
import com.agentic.sdlc.state.StageAttemptRepository;
import com.agentic.sdlc.state.StageRun;
import com.agentic.sdlc.state.StageRunRepository;
import com.agentic.sdlc.state.StageStatus;
import com.agentic.sdlc.state.WorkflowRun;
import com.agentic.sdlc.state.WorkflowRunRepository;
import com.agentic.sdlc.workflow.NodeDefinition;
import com.agentic.sdlc.workflow.WorkflowCatalog;
import com.agentic.sdlc.workflow.WorkflowDefinition;
import com.agentic.sdlc.workspace.Workspace;
import com.agentic.sdlc.workspace.WorkspaceService;

import tools.jackson.databind.ObjectMapper;

/**
 * The scheduler and the only writer of run-level state. Every state transition happens inside a per-run
 * lock; stages execute outside the lock on virtual threads and call {@link #advance} when they finish.
 *
 * <p>Scheduling rule: a PENDING stage starts when all its dependencies are SUCCEEDED or SKIPPED, no stage in
 * the run has FAILED, and the run is not STOPPED. Stages that become ready together run in parallel; a stage
 * with several dependencies is the synchronization point for them.
 */
@Service
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    private final WorkflowCatalog catalog;
    private final WorkflowRunRepository runs;
    private final StageRunRepository stages;
    private final StageAttemptRepository attempts;
    private final ApprovalRequestRepository approvals;
    private final DecisionRepository decisions;
    private final ArtifactStore artifacts;
    private final ConditionRegistry conditions;
    private final NodeExecutor executor;
    private final RunSignals signals;
    private final AuditService audit;
    private final ExecutorService stageExecutor;
    private final WorkspaceService workspaces;
    private final ObjectMapper json;

    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    public WorkflowEngine(WorkflowCatalog catalog, WorkflowRunRepository runs, StageRunRepository stages,
            StageAttemptRepository attempts, ApprovalRequestRepository approvals, DecisionRepository decisions,
            ArtifactStore artifacts, ConditionRegistry conditions, NodeExecutor executor, RunSignals signals,
            AuditService audit, @Qualifier("stageExecutor") ExecutorService stageExecutor,
            WorkspaceService workspaces, ObjectMapper json) {
        this.catalog = catalog;
        this.runs = runs;
        this.stages = stages;
        this.attempts = attempts;
        this.approvals = approvals;
        this.decisions = decisions;
        this.artifacts = artifacts;
        this.conditions = conditions;
        this.executor = executor;
        this.signals = signals;
        this.audit = audit;
        this.stageExecutor = stageExecutor;
        this.workspaces = workspaces;
        this.json = json;
    }

    // ---------------------------------------------------------------- run lifecycle

    public WorkflowRun start(String workflowName, String requirement, Scenario scenario, String startedBy) {
        return start(workflowName, requirement, scenario, startedBy, null);
    }

    /**
     * @param recording LLM recording name: written in record mode, played back in replay mode
     */
    public WorkflowRun start(String workflowName, String requirement, Scenario scenario, String startedBy,
            String recording) {
        WorkflowDefinition workflow = catalog.get(workflowName);
        WorkflowRun run = new WorkflowRun();
        run.setId(UUID.randomUUID().toString());
        run.setRecording(recording);
        Workspace workspace = workflow.usesWorkspace() ? workspaces.prepare(run.getId()) : null;
        if (workspace != null) {
            run.setBaselineCommit(workspace.headCommit());
        }
        run.setWorkflowName(workflow.name());
        run.setRequirement(requirement);
        run.setScenario(scenario == null ? Scenario.GREENFIELD : scenario);
        run.setStatus(RunStatus.RUNNING);
        run.setStartedBy(startedBy);
        run.setCreatedAt(Instant.now());
        run.setBudgetWindowStart(run.getCreatedAt());
        runs.save(run);
        for (NodeDefinition node : workflow.topologicalOrder()) {
            stages.save(new StageRun(run.getId(), node.id()));
        }
        audit.record(run.getId(), null, AuditType.RUN_STARTED, AuditService.humanActor(startedBy),
                "Started workflow " + workflow.name() + " (" + run.getScenario() + ")",
                Map.of("requirement", requirement));
        if (workspace != null) {
            audit.record(run.getId(), null, AuditType.WORKSPACE_PREPARED, AuditService.SYSTEM,
                    "Cloned target repository at " + Hashing.shortHash(run.getBaselineCommit()) + " onto branch "
                            + workspace.branch(),
                    Map.of("path", workspace.root().toString(), "baseline", run.getBaselineCommit()));
        }
        advance(run.getId());
        return run;
    }

    /** Safe-stop: no new stages start, running agents stop at their next checkpoint, state is kept for resume. */
    public void stop(String runId, String actor, String reason) {
        withLock(runId, () -> {
            WorkflowRun run = load(runId);
            if (run.getStatus().isFinished() || run.getStatus() == RunStatus.STOPPED) {
                throw new ConflictException("Run " + runId + " is already " + run.getStatus());
            }
            signals.requestStop(runId, reason);
            run.setStatus(RunStatus.STOPPED);
            run.setStopReason(reason);
            runs.save(run);
            audit.record(runId, null, AuditType.RUN_STOPPED, actor, "Run stopped: " + reason);
            return null;
        });
    }

    /** Resumes a stopped run. The human resuming it grants a fresh budget window. */
    public void resume(String runId, String actor) {
        withLock(runId, () -> {
            WorkflowRun run = load(runId);
            if (run.getStatus() != RunStatus.STOPPED) {
                throw new ConflictException("Run " + runId + " is " + run.getStatus() + ", not STOPPED");
            }
            signals.clearStop(runId);
            run.setStatus(RunStatus.RUNNING);
            run.setStopReason(null);
            run.setBudgetWindowStart(Instant.now());
            runs.save(run);
            audit.record(runId, null, AuditType.RUN_RESUMED, actor, "Run resumed with a fresh budget window");
            advance(runId);
            return null;
        });
    }

    /** Manual recovery after a stage exhausted its retries and fallback. */
    public void retryStage(String runId, String nodeId, String actor) {
        rerunStage(runId, nodeId, actor, null);
    }

    /**
     * Sends a stage back for another pass. A FAILED stage is simply re-queued; a SUCCEEDED stage has its
     * outputs and code checkpoints rolled back first, and everything downstream of it is re-planned.
     * {@code feedback} reaches the agent like a reviewer comment.
     */
    public void rerunStage(String runId, String nodeId, String actor, String feedback) {
        withLock(runId, () -> {
            WorkflowRun run = load(runId);
            if (run.getStatus() == RunStatus.STOPPED) {
                throw new ConflictException("Run is stopped; resume it first");
            }
            WorkflowDefinition workflow = catalog.get(run.getWorkflowName());
            StageRun stage = stage(runId, nodeId);
            if (stage.getStatus() == StageStatus.SUCCEEDED) {
                requireNotRunning(runId, workflow, nodeId);
                invalidateDownstream(run, workflow, nodeId, nodeId + " sent back by " + actor);
                withdrawStage(runId, workflow, stage, "rerun");
            } else if (stage.getStatus() != StageStatus.FAILED) {
                throw new ConflictException("Stage " + nodeId + " is " + stage.getStatus()
                        + "; only FAILED or SUCCEEDED stages can be re-run");
            }
            stage.setStatus(StageStatus.PENDING);
            stage.setLastError(null);
            if (feedback != null && !feedback.isBlank()) {
                stage.setHumanFeedback(feedback);
            }
            stages.save(stage);
            reopen(run);
            audit.record(runId, nodeId, AuditType.STAGE_MANUAL_RETRY, actor,
                    "Stage re-queued by a human" + (feedback == null ? "" : ": " + feedback));
            if (feedback != null && !feedback.isBlank()) {
                recordHumanDecision(run, nodeId, actor, "Sent back " + nodeId, feedback, Map.of());
            }
            advance(runId);
            return null;
        });
    }

    // ---------------------------------------------------------------- human checkpoints

    public void approve(Long approvalId, String actor, String comment) {
        ApprovalRequest request = approval(approvalId);
        withLock(request.getRunId(), () -> {
            ApprovalRequest current = requirePending(approvalId);
            StageRun stage = stages.findById(current.getStageRunId()).orElseThrow();
            WorkflowRun run = load(stage.getRunId());
            WorkflowDefinition workflow = catalog.get(run.getWorkflowName());
            if (workflow.node(stage.getNodeId()).publishes() && workflow.usesWorkspace()) {
                // Publishing first: if main moved, the approval stays pending and nothing is half-released.
                String published = workspaces.publish(run.getId());
                run.setPublishedCommit(published);
                runs.save(run);
                audit.record(run.getId(), stage.getNodeId(), AuditType.RELEASE_PUBLISHED, actor,
                        "Fast-forwarded target main to " + Hashing.shortHash(published),
                        Map.of("commit", published, "repository", workspaces.targetRepo().toString()));
            }
            decide(current, ApprovalStatus.APPROVED, actor, comment);
            recordApprovalDecision(run, current, "Approved", actor, comment);
            stage.setStatus(StageStatus.SUCCEEDED);
            stage.setEndedAt(Instant.now());
            stages.save(stage);
            audit.record(stage.getRunId(), stage.getNodeId(), AuditType.APPROVAL_GRANTED, actor,
                    current.getReason() + " approved" + (comment == null ? "" : ": " + comment));
            advance(stage.getRunId());
            return null;
        });
    }

    /** Rejection withdraws the stage's outputs and re-runs it with the reviewer's comment as feedback. */
    public void reject(Long approvalId, String actor, String comment) {
        if (comment == null || comment.isBlank()) {
            throw new IllegalArgumentException("A rejection must say what to change");
        }
        ApprovalRequest request = approval(approvalId);
        withLock(request.getRunId(), () -> {
            ApprovalRequest current = requirePending(approvalId);
            StageRun stage = stages.findById(current.getStageRunId()).orElseThrow();
            decide(current, ApprovalStatus.REJECTED, actor, comment);
            recordApprovalDecision(load(stage.getRunId()), current, "Rejected", actor, comment);
            audit.record(stage.getRunId(), stage.getNodeId(), AuditType.APPROVAL_REJECTED, actor,
                    current.getReason() + " rejected: " + comment);
            WorkflowDefinition workflow = catalog.get(load(stage.getRunId()).getWorkflowName());
            withdrawStage(stage.getRunId(), workflow, stage, "rejection");
            stage.setStatus(StageStatus.PENDING);
            stage.setHumanFeedback(comment);
            stages.save(stage);
            advance(stage.getRunId());
            return null;
        });
    }

    // ---------------------------------------------------------------- dynamic re-planning

    /**
     * A human edits a committed artifact. Every downstream stage built on the old version is invalidated and
     * re-scheduled (optional stages re-evaluate their conditions); unrelated branches keep their results.
     */
    public Artifact reviseArtifact(String runId, String artifactName, String content, String actor, String reason) {
        return withLock(runId, () -> {
            WorkflowRun run = load(runId);
            WorkflowDefinition workflow = catalog.get(run.getWorkflowName());
            String producer = workflow.producerOf(artifactName)
                    .orElseThrow(() -> new NotFoundException("No stage produces artifact " + artifactName));
            if (!workflow.node(producer).writes().isEmpty()) {
                throw new ConflictException(artifactName + " describes code changes; re-run stage " + producer
                        + " with feedback instead of editing the artifact");
            }
            Artifact current = artifacts.committed(runId, artifactName)
                    .orElseThrow(() -> new ConflictException(artifactName + " has not been committed yet"));
            requireNotRunning(runId, workflow, producer);
            String newHash = Hashing.sha256(content);
            if (newHash.equals(current.getContentHash())) {
                return current;
            }

            Artifact revised = artifacts.revise(current, content, actor);
            recordHumanDecision(run, producer, actor, "Revised " + artifactName, reason,
                    Map.of(artifactName, current.getContentHash() + " -> " + newHash));
            audit.record(runId, producer, AuditType.ARTIFACT_REVISED, actor,
                    artifactName + " v" + current.getVersion() + " -> v" + revised.getVersion() + ": " + reason,
                    Map.of("oldHash", current.getContentHash(), "newHash", newHash));

            String cause = artifactName + " changed " + Hashing.shortHash(current.getContentHash()) + " -> "
                    + Hashing.shortHash(newHash);
            List<String> invalidated = invalidateDownstream(run, workflow, producer, cause);
            if (!invalidated.isEmpty() && run.getStatus() != RunStatus.STOPPED) {
                reopen(run);
            }
            advance(runId);
            return revised;
        });
    }

    /**
     * Resets every started stage downstream of {@code producer} to PENDING, withdrawing its outputs and
     * reverting its code checkpoints (newest stage first, so reverts apply cleanly).
     */
    private List<String> invalidateDownstream(WorkflowRun run, WorkflowDefinition workflow, String producer,
            String cause) {
        List<StageRun> affected = new ArrayList<>();
        for (String nodeId : workflow.descendantsOf(producer)) {
            StageRun stage = stage(run.getId(), nodeId);
            if (stage.getStatus() != StageStatus.PENDING) {
                affected.add(stage);
            }
        }
        List<StageRun> newestFirst = new ArrayList<>(affected);
        newestFirst.sort((a, b) -> Integer.compare(order(workflow, b.getNodeId()), order(workflow, a.getNodeId())));
        for (StageRun stage : newestFirst) {
            withdrawStage(run.getId(), workflow, stage, "replan");
        }
        for (StageRun stage : affected) {
            for (ApprovalRequest open : approvals.findByStageRunIdAndStatus(stage.getId(), ApprovalStatus.PENDING)) {
                decide(open, ApprovalStatus.CANCELLED, AuditService.SYSTEM, "Invalidated by re-planning");
            }
            StageStatus previous = stage.getStatus();
            stage.setStatus(StageStatus.PENDING);
            stage.setLastError(null);
            stage.setEndedAt(null);
            stages.save(stage);
            audit.record(run.getId(), stage.getNodeId(), AuditType.STAGE_INVALIDATED, AuditService.SYSTEM,
                    previous + " -> PENDING because upstream " + cause);
        }
        return affected.stream().map(StageRun::getNodeId).toList();
    }

    /** Withdraws a stage's committed artifacts and reverts its git checkpoints. */
    private void withdrawStage(String runId, WorkflowDefinition workflow, StageRun stage, String scope) {
        preserveStageFiles(runId, workflow, stage);
        int withdrawn = artifacts.supersedeStageOutputs(runId, stage.getNodeId());
        List<String> reverted = workflow.usesWorkspace()
                ? workspaces.find(runId).map(ws -> ws.revertStage(stage.getNodeId(), load(runId).getBaselineCommit()))
                        .orElse(List.of())
                : List.of();
        if (withdrawn > 0 || !reverted.isEmpty()) {
            audit.record(runId, stage.getNodeId(), AuditType.ROLLBACK, AuditService.SYSTEM,
                    "Withdrew " + withdrawn + " output(s)" + (reverted.isEmpty() ? "" : " and reverted "
                            + reverted.size() + " checkpoint commit(s)"),
                    Map.of("scope", scope, "revertedCommits", reverted));
        }
    }

    /**
     * Before a code stage is reverted, keep its files as the stage's last proposal: the rework then repairs
     * the previous version with the new feedback instead of regenerating it and dropping earlier fixes.
     */
    private void preserveStageFiles(String runId, WorkflowDefinition workflow, StageRun stage) {
        if (!workflow.usesWorkspace() || workflow.node(stage.getNodeId()).writes().isEmpty()) {
            return;
        }
        workspaces.find(runId).ifPresent(ws -> {
            // Merge over the existing repair base: a fragmentary last attempt must not replace a fuller version.
            Map<String, String> files = new java.util.LinkedHashMap<>(executor.lastProposal(runId, stage.getNodeId()));
            files.putAll(ws.stageFiles(stage.getNodeId(), load(runId).getBaselineCommit()));
            if (!files.isEmpty()) {
                artifacts.discard(runId, stage.getNodeId(), stage.getId(), null, AuditService.SYSTEM,
                        Map.of(NodeExecutor.proposalArtifact(stage.getNodeId()), json.writeValueAsString(files)));
            }
        });
    }

    private void requireNotRunning(String runId, WorkflowDefinition workflow, String nodeId) {
        List<String> affected = new ArrayList<>(List.of(nodeId));
        affected.addAll(workflow.descendantsOf(nodeId));
        List<String> busy = affected.stream()
                .filter(id -> stage(runId, id).getStatus() == StageStatus.RUNNING).toList();
        if (!busy.isEmpty()) {
            throw new ConflictException("Stages " + busy + " are running; wait or stop the run first");
        }
    }

    private static int order(WorkflowDefinition workflow, String nodeId) {
        List<NodeDefinition> order = workflow.topologicalOrder();
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).id().equals(nodeId)) {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- scheduling

    /** Starts every stage that is ready, then recomputes the run status. Safe to call at any time. */
    public void advance(String runId) {
        withLock(runId, () -> {
            WorkflowRun run = load(runId);
            if (run.getStatus() == RunStatus.STOPPED || run.getStatus().isFinished()) {
                return null;
            }
            WorkflowDefinition workflow = catalog.get(run.getWorkflowName());
            Map<String, StageRun> byNode = stagesByNode(runId);
            boolean anyFailed = byNode.values().stream().anyMatch(s -> s.getStatus() == StageStatus.FAILED);

            for (NodeDefinition node : workflow.topologicalOrder()) {
                StageRun stage = byNode.get(node.id());
                if (anyFailed || stage.getStatus() != StageStatus.PENDING || !dependenciesMet(node, byNode)) {
                    continue;
                }
                if (node.condition() != null && !conditions.get(node.condition()).test(conditionContext(run))) {
                    stage.setStatus(StageStatus.SKIPPED);
                    stages.save(stage);
                    audit.record(runId, node.id(), AuditType.STAGE_SKIPPED, AuditService.SYSTEM,
                            "Condition " + node.condition() + " is false");
                    continue;
                }
                stage.setStatus(StageStatus.RUNNING);
                stages.save(stage);
                launch(workflow, runId, node.id());
            }
            updateRunStatus(run, byNode.values());
            return null;
        });
    }

    private void launch(WorkflowDefinition workflow, String runId, String nodeId) {
        inFlight.computeIfAbsent(runId, k -> new AtomicInteger()).incrementAndGet();
        CompletableFuture.supplyAsync(() -> executor.execute(workflow, runId, nodeId), stageExecutor)
                .whenComplete((result, error) -> {
                    try {
                        onStageFinished(runId, nodeId, result, error);
                    } finally {
                        inFlight.get(runId).decrementAndGet();
                    }
                });
    }

    private void onStageFinished(String runId, String nodeId, NodeExecutor.Result result, Throwable error) {
        if (error != null) {
            log.error("Stage {} of run {} crashed", nodeId, runId, error);
            withLock(runId, () -> {
                StageRun stage = stage(runId, nodeId);
                stage.setStatus(StageStatus.FAILED);
                stage.setLastError(Text.truncate("Orchestrator error: " + error.getMessage(), 4000));
                stage.setEndedAt(Instant.now());
                stages.save(stage);
                attempts.findByRunIdOrderByIdAsc(runId).stream()
                        .filter(a -> a.getStageRunId().equals(stage.getId()) && a.getStatus() == AttemptStatus.RUNNING)
                        .forEach(a -> {
                            a.setStatus(AttemptStatus.ABORTED);
                            a.setFailureReason("orchestrator error");
                            a.setEndedAt(Instant.now());
                            attempts.save(a);
                        });
                audit.record(runId, nodeId, AuditType.STAGE_FAILED, AuditService.SYSTEM,
                        "Stage crashed: " + error.getMessage());
                return null;
            });
        } else if (result.outcome() == NodeExecutor.Outcome.BUDGET_EXCEEDED) {
            audit.record(runId, nodeId, AuditType.BUDGET_EXCEEDED, AuditService.SYSTEM, result.detail());
            withLock(runId, () -> {
                if (load(runId).getStatus() != RunStatus.STOPPED) {
                    stop(runId, AuditService.SYSTEM, "Budget exceeded: " + result.detail());
                }
                return null;
            });
        }
        advance(runId);
    }

    private void updateRunStatus(WorkflowRun run, Iterable<StageRun> all) {
        boolean running = false, waiting = false, failed = false, done = true;
        for (StageRun stage : all) {
            StageStatus status = stage.getStatus();
            running |= status == StageStatus.RUNNING;
            waiting |= status == StageStatus.AWAITING_APPROVAL;
            failed |= status == StageStatus.FAILED;
            done &= status.satisfiesDependency();
        }
        RunStatus next = running ? RunStatus.RUNNING
                : waiting ? RunStatus.AWAITING_APPROVAL
                : failed ? RunStatus.FAILED
                : done ? RunStatus.SUCCEEDED
                : RunStatus.RUNNING;
        if (next != run.getStatus()) {
            RunStatus previous = run.getStatus();
            run.setStatus(next);
            run.setEndedAt(next.isFinished() ? Instant.now() : null);
            runs.save(run);
            audit.record(run.getId(), null, AuditType.RUN_STATUS_CHANGED, AuditService.SYSTEM, previous + " -> " + next);
        }
    }

    private static boolean dependenciesMet(NodeDefinition node, Map<String, StageRun> byNode) {
        return node.dependsOn().stream().allMatch(dep -> byNode.get(dep).getStatus().satisfiesDependency());
    }

    private ConditionContext conditionContext(WorkflowRun run) {
        return new ConditionContext(run.getId(), run.getScenario(), name -> artifacts.readCommitted(run.getId(), name));
    }

    // ---------------------------------------------------------------- recovery

    /** After a crash or restart, work that was mid-flight is re-queued instead of being left RUNNING forever. */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedRuns() {
        for (WorkflowRun run : runs.findAll()) {
            if (run.getStatus() != RunStatus.RUNNING && run.getStatus() != RunStatus.AWAITING_APPROVAL) {
                continue;
            }
            int requeued = 0;
            for (StageRun stage : stages.findByRunIdOrderByIdAsc(run.getId())) {
                if (stage.getStatus() == StageStatus.RUNNING) {
                    stage.setStatus(StageStatus.PENDING);
                    stages.save(stage);
                    requeued++;
                }
            }
            attempts.findByRunIdOrderByIdAsc(run.getId()).stream()
                    .filter(a -> a.getStatus() == AttemptStatus.RUNNING)
                    .forEach(a -> {
                        a.setStatus(AttemptStatus.ABORTED);
                        a.setFailureReason("orchestrator restarted");
                        a.setEndedAt(Instant.now());
                        attempts.save(a);
                    });
            if (requeued > 0) {
                audit.record(run.getId(), null, AuditType.RUN_RECOVERED, AuditService.SYSTEM,
                        "Re-queued " + requeued + " interrupted stage(s) after restart");
            }
            advance(run.getId());
        }
    }

    // ---------------------------------------------------------------- test and demo support

    /** Blocks until no stage of the run is executing (it may still be waiting for a human). */
    public void awaitIdle(String runId, Duration timeout) throws TimeoutException, InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (inFlight.getOrDefault(runId, new AtomicInteger()).get() > 0) {
            if (Instant.now().isAfter(deadline)) {
                throw new TimeoutException("Run " + runId + " still has stages executing after " + timeout);
            }
            Thread.sleep(10);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void reopen(WorkflowRun run) {
        if (run.getStatus() != RunStatus.RUNNING) {
            run.setStatus(RunStatus.RUNNING);
            run.setEndedAt(null);
            runs.save(run);
        }
    }

    private void decide(ApprovalRequest request, ApprovalStatus status, String actor, String comment) {
        request.setStatus(status);
        request.setDecidedBy(actor);
        request.setComment(comment);
        request.setDecidedAt(Instant.now());
        approvals.save(request);
    }

    /** A reviewer's comment is a decision downstream agents must respect, so it joins the decision lineage. */
    private void recordApprovalDecision(WorkflowRun run, ApprovalRequest request, String verdict, String actor,
            String comment) {
        if (comment == null || comment.isBlank()) {
            return;
        }
        recordHumanDecision(run, request.getNodeId(), actor,
                verdict + " " + request.getReason() + " at " + request.getNodeId(), comment, Map.of());
    }

    private void recordHumanDecision(WorkflowRun run, String nodeId, String actor, String title, String rationale,
            Map<String, String> hashes) {
        Decision decision = new Decision();
        decision.setRunId(run.getId());
        decision.setNodeId(nodeId);
        decision.setStageRunId(stage(run.getId(), nodeId).getId());
        decision.setActor(actor);
        decision.setTitle(title);
        decision.setRationale(rationale);
        decision.setInputHashes(hashes.toString());
        decision.setCreatedAt(Instant.now());
        decisions.save(decision);
    }

    private ApprovalRequest approval(Long approvalId) {
        return approvals.findById(approvalId)
                .orElseThrow(() -> new NotFoundException("Unknown approval " + approvalId));
    }

    private ApprovalRequest requirePending(Long approvalId) {
        ApprovalRequest request = approval(approvalId);
        if (request.getStatus() != ApprovalStatus.PENDING) {
            throw new ConflictException("Approval " + approvalId + " is already " + request.getStatus());
        }
        return request;
    }

    private WorkflowRun load(String runId) {
        return runs.findById(runId).orElseThrow(() -> new NotFoundException("Unknown run " + runId));
    }

    private StageRun stage(String runId, String nodeId) {
        return stages.findByRunIdAndNodeId(runId, nodeId)
                .orElseThrow(() -> new NotFoundException("Run " + runId + " has no stage " + nodeId));
    }

    private Map<String, StageRun> stagesByNode(String runId) {
        Map<String, StageRun> byNode = new LinkedHashMap<>();
        stages.findByRunIdOrderByIdAsc(runId).forEach(s -> byNode.put(s.getNodeId(), s));
        return byNode;
    }

    private <T> T withLock(String runId, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(runId, k -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
