package com.agentic.sdlc.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.agentic.sdlc.agent.Agent;
import com.agentic.sdlc.agent.AgentContext;
import com.agentic.sdlc.agent.AgentRegistry;
import com.agentic.sdlc.agent.AgentResult;
import com.agentic.sdlc.agent.ProposedDecision;
import com.agentic.sdlc.agent.RunStoppedException;
import com.agentic.sdlc.audit.AuditService;
import com.agentic.sdlc.audit.AuditType;
import com.agentic.sdlc.common.Text;
import com.agentic.sdlc.config.SdlcProperties;
import com.agentic.sdlc.gate.GateContext;
import com.agentic.sdlc.gate.GateRegistry;
import com.agentic.sdlc.gate.GateResult;
import com.agentic.sdlc.policy.PolicyDecision;
import com.agentic.sdlc.policy.PolicyEngine;
import com.agentic.sdlc.policy.PolicyFinding;
import com.agentic.sdlc.policy.PolicyVerdict;
import com.agentic.sdlc.policy.ProposedChange;
import com.agentic.sdlc.state.ApprovalReason;
import com.agentic.sdlc.state.ApprovalRequest;
import com.agentic.sdlc.state.ApprovalRequestRepository;
import com.agentic.sdlc.state.ApprovalStatus;
import com.agentic.sdlc.state.ArtifactStatus;
import com.agentic.sdlc.state.AttemptStatus;
import com.agentic.sdlc.state.Decision;
import com.agentic.sdlc.state.DecisionRepository;
import com.agentic.sdlc.state.StageAttempt;
import com.agentic.sdlc.state.StageAttemptRepository;
import com.agentic.sdlc.state.StageRun;
import com.agentic.sdlc.state.StageRunRepository;
import com.agentic.sdlc.state.StageStatus;
import com.agentic.sdlc.state.WorkflowRun;
import com.agentic.sdlc.state.WorkflowRunRepository;
import com.agentic.sdlc.workflow.NodeDefinition;
import com.agentic.sdlc.workflow.WorkflowDefinition;
import com.agentic.sdlc.workspace.Workspace;
import com.agentic.sdlc.workspace.WorkspaceService;
import com.agentic.sdlc.workspace.WorkspaceSession;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs one stage to a resting state: entry gates, then agent attempts (bounded retries, then fallback), each
 * checked by policies and exit gates. Only a passing attempt's outputs are committed; everything else is
 * rolled back. The engine owns scheduling; this class owns a single stage.
 */
@Component
public class NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(NodeExecutor.class);

    /** Longest single failure passed back to an agent; enough for ~15 test failures or ~25 compiler errors. */
    static final int FEEDBACK_CHARS = 12_000;
    /** Matches the 4000-character columns on StageRun.lastError and StageAttempt.failureReason. */
    static final int STORED_REASON_CHARS = 4000;

    /** How a stage execution ended. The engine decides what happens to the run next. */
    public enum Outcome {
        SUCCEEDED, AWAITING_APPROVAL, FAILED, INTERRUPTED, BUDGET_EXCEEDED
    }

    public record Result(Outcome outcome, String detail) {
    }

    private record AgentPlan(String agent, int attempts, boolean fallback) {
    }

    private record GateVerdict(GateResult.Outcome outcome, String message, Map<String, Object> evidence) {
    }

    /** @param proposedFiles files a failed attempt wrote (before rollback), handed to the next attempt */
    private record AttemptOutcome(Outcome outcome, String failure, Map<String, String> proposedFiles) {

        AttemptOutcome(Outcome outcome, String failure) {
            this(outcome, failure, Map.of());
        }
    }

    private final WorkflowRunRepository runs;
    private final StageRunRepository stages;
    private final StageAttemptRepository attempts;
    private final ApprovalRequestRepository approvals;
    private final DecisionRepository decisions;
    private final ArtifactStore artifacts;
    private final AgentRegistry agents;
    private final GateRegistry gates;
    private final PolicyEngine policies;
    private final BudgetGuard budget;
    private final RunSignals signals;
    private final AuditService audit;
    private final SdlcProperties properties;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final WorkspaceService workspaces;

    public NodeExecutor(WorkflowRunRepository runs, StageRunRepository stages, StageAttemptRepository attempts,
            ApprovalRequestRepository approvals, DecisionRepository decisions, ArtifactStore artifacts,
            AgentRegistry agents, GateRegistry gates, PolicyEngine policies, BudgetGuard budget, RunSignals signals,
            AuditService audit, SdlcProperties properties, ObjectMapper json, TransactionTemplate tx,
            MeterRegistry meters, WorkspaceService workspaces) {
        this.runs = runs;
        this.stages = stages;
        this.attempts = attempts;
        this.approvals = approvals;
        this.decisions = decisions;
        this.artifacts = artifacts;
        this.agents = agents;
        this.gates = gates;
        this.policies = policies;
        this.budget = budget;
        this.signals = signals;
        this.audit = audit;
        this.properties = properties;
        this.json = json;
        this.tx = tx;
        this.meters = meters;
        this.workspaces = workspaces;
    }

    public Result execute(WorkflowDefinition workflow, String runId, String nodeId) {
        NodeDefinition node = workflow.node(nodeId);
        WorkflowRun run = runs.findById(runId).orElseThrow();
        StageRun stage = stages.findByRunIdAndNodeId(runId, nodeId).orElseThrow();
        List<String> inputs = expectedInputs(workflow, runId, nodeId);
        Optional<Workspace> workspace = workflow.usesWorkspace() ? workspaces.find(runId) : Optional.empty();

        List<String> feedback = new ArrayList<>();
        if (stage.getHumanFeedback() != null) {
            feedback.add("Human reviewer: " + stage.getHumanFeedback());
            stage.setHumanFeedback(null);
        }
        if (stage.getStartedAt() == null) {
            stage.setStartedAt(Instant.now());
        }
        stage.setEndedAt(null);
        stage.setLastError(null);
        stages.save(stage);
        audit.record(runId, nodeId, AuditType.STAGE_STARTED, AuditService.SYSTEM, "Stage started");

        GateVerdict entry = runGates(runId, node.entryGates(),
                new GateContext(runId, node, inputs, name -> artifacts.readCommitted(runId, name), workspace));
        if (entry.outcome() != GateResult.Outcome.PASS) {
            // Retrying cannot fix missing inputs, so escalate straight away.
            return fail(stage, "Entry gate failed: " + entry.message());
        }

        String lastFailure = null;
        Map<String, String> previousFiles = lastProposal(runId, nodeId);
        for (AgentPlan plan : plansFor(node)) {
            if (plan.fallback()) {
                audit.record(runId, nodeId, AuditType.FALLBACK_ACTIVATED, AuditService.SYSTEM,
                        "Primary agent exhausted; switching to fallback " + plan.agent());
            }
            for (int i = 0; i < plan.attempts(); i++) {
                if (i > 0 || plan.fallback()) {
                    pause(properties.retryBackoff());
                }
                Optional<String> exceeded = budget.exceeded(run);
                if (exceeded.isPresent()) {
                    interrupt(stage, "budget exceeded");
                    return new Result(Outcome.BUDGET_EXCEEDED, exceeded.get());
                }
                if (signals.isStopRequested(runId)) {
                    interrupt(stage, "run stopped");
                    return new Result(Outcome.INTERRUPTED, "run stopped");
                }
                AttemptOutcome attempt = runAttempt(run, workflow, node, stage, plan, feedback, inputs, workspace,
                        previousFiles);
                if (attempt.outcome() != Outcome.FAILED) {
                    return new Result(attempt.outcome(), attempt.failure());
                }
                lastFailure = Text.truncate(attempt.failure(), FEEDBACK_CHARS);
                if (!attempt.proposedFiles().isEmpty()) {
                    // Merge, don't replace: an attempt that edited only some files must not make the next
                    // attempt forget the rest of the version it is repairing.
                    Map<String, String> merged = new LinkedHashMap<>(previousFiles);
                    merged.putAll(attempt.proposedFiles());
                    previousFiles = merged;
                }
                feedback.add(lastFailure);
            }
        }
        return fail(stage, "All attempts exhausted. Last failure: " + lastFailure);
    }

    private AttemptOutcome runAttempt(WorkflowRun run, WorkflowDefinition workflow, NodeDefinition node,
            StageRun stage, AgentPlan plan, List<String> feedback, List<String> inputs,
            Optional<Workspace> workspace, Map<String, String> previousFiles) {
        String runId = run.getId();
        Agent agent = agents.get(plan.agent());
        String actor = AuditService.agentActor(agent.name());

        stage.setAttemptCount(stage.getAttemptCount() + 1);
        stages.save(stage);
        StageAttempt attempt = new StageAttempt();
        attempt.setRunId(runId);
        attempt.setStageRunId(stage.getId());
        attempt.setNodeId(node.id());
        attempt.setAttemptNo(stage.getAttemptCount());
        attempt.setAgent(agent.name());
        attempt.setFallback(plan.fallback());
        attempt.setStatus(AttemptStatus.RUNNING);
        attempt.setStartedAt(Instant.now());
        attempts.save(attempt);

        WorkspaceSession session = workspace.map(w -> w.session(node.id(), attempt.getAttemptNo(), actor)).orElse(null);
        if (session != null && !previousFiles.isEmpty()) {
            restoreRepairBase(session, node, previousFiles);
        }
        AgentContext context = new AgentContext(runId, run.getRequirement(), run.getScenario(), node,
                attempt.getAttemptNo(), feedback, name -> artifacts.readCommitted(runId, name),
                () -> signals.isStopRequested(runId), session, run.getRecording(), plan.fallback(), previousFiles,
                humanDecisions(runId));

        String failure = null;
        try {
            AgentResult result = agent.execute(context);
            if (!result.success()) {
                failure = "Agent reported failure: " + result.summary();
            }
        } catch (RunStoppedException e) {
            rollbackFiles(runId, node.id(), session, attempt.getAttemptNo());
            finishAttempt(attempt, AttemptStatus.ABORTED, "run stopped", context.tokensUsed());
            interrupt(stage, "run stopped during attempt " + attempt.getAttemptNo());
            return new AttemptOutcome(Outcome.INTERRUPTED, "run stopped");
        } catch (Exception e) {
            log.warn("Agent {} threw on run {} stage {}", agent.name(), runId, node.id(), e);
            failure = "Agent error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        Map<String, String> staged = context.stagedArtifacts();
        ApprovalReason approvalReason = null;
        String approvalSummary = null;
        Map<String, Object> evidence = Map.of();

        if (failure == null) {
            PolicyDecision policy = policies.evaluate(new ProposedChange(runId, node.id(), staged,
                    session == null ? List.of() : session.changes(), node.writes()));
            for (PolicyFinding finding : policy.findings()) {
                audit.record(runId, node.id(), AuditType.POLICY_FINDING, AuditService.SYSTEM,
                        finding.rule() + " -> " + finding.verdict() + ": " + finding.message());
            }
            if (policy.verdict() == PolicyVerdict.DENY) {
                failure = "Policy violation: " + policy.describe(PolicyVerdict.DENY);
            } else if (policy.verdict() == PolicyVerdict.REQUIRE_APPROVAL) {
                approvalReason = ApprovalReason.POLICY;
                approvalSummary = policy.describe(PolicyVerdict.REQUIRE_APPROVAL);
            }
        }
        if (failure == null) {
            Function<String, Optional<String>> view = name -> staged.containsKey(name)
                    ? Optional.of(staged.get(name)) : artifacts.readCommitted(runId, name);
            GateVerdict exit = runGates(runId, node.exitGates(), new GateContext(runId, node, inputs, view, workspace));
            evidence = exit.evidence();
            if (exit.outcome() == GateResult.Outcome.FAIL) {
                failure = "Exit gate failed: " + exit.message();
            } else if (exit.outcome() == GateResult.Outcome.NEEDS_HUMAN) {
                approvalReason = ApprovalReason.CLARIFICATION;
                approvalSummary = exit.message();
            }
        }

        if (failure != null) {
            Map<String, String> proposed = new LinkedHashMap<>();
            if (session != null) {
                session.changes().stream().filter(c -> !c.isDelete()).forEach(c -> proposed.put(c.path(), c.content()));
            }
            rollbackFiles(runId, node.id(), session, attempt.getAttemptNo());
            Map<String, String> discarded = new LinkedHashMap<>(staged);
            if (!proposed.isEmpty()) {
                Map<String, String> repairBase = new LinkedHashMap<>(previousFiles);
                repairBase.putAll(proposed);
                discarded.put(proposalArtifact(node.id()), json.writeValueAsString(repairBase));
            }
            artifacts.discard(runId, node.id(), stage.getId(), attempt.getId(), actor, discarded);
            finishAttempt(attempt, AttemptStatus.FAILED, failure, context.tokensUsed());
            audit.record(runId, node.id(), AuditType.ATTEMPT_FAILED, actor,
                    "Attempt " + attempt.getAttemptNo() + " failed: " + failure);
            if (!staged.isEmpty()) {
                audit.record(runId, node.id(), AuditType.ROLLBACK, AuditService.SYSTEM,
                        "Discarded proposed outputs " + staged.keySet() + " of attempt " + attempt.getAttemptNo(),
                        Map.of("scope", "attempt"));
            }
            return new AttemptOutcome(Outcome.FAILED, failure, proposed);
        }

        if (approvalReason == null && node.needsApproval()) {
            approvalReason = ApprovalReason.STAGE_CHECKPOINT;
            approvalSummary = "Review " + node.outputs() + " before downstream stages use them";
        }
        if (session != null) {
            String sha = session.checkpoint(node.id() + " by " + agent.name());
            if (sha != null) {
                attempt.setCheckpointCommit(sha);
                audit.record(runId, node.id(), AuditType.CHECKPOINT, actor,
                        "Checkpoint commit " + sha.substring(0, 10) + " on " + session.workspace().branch(),
                        Map.of("commit", sha));
            }
        }
        Map<String, String> outputs = new LinkedHashMap<>(staged);
        if (!evidence.isEmpty()) {
            outputs.put(node.id() + "_evidence", json.writeValueAsString(evidence));
        }
        Outcome outcome = commit(workflow, node, stage, attempt, actor, context, outputs, approvalReason,
                approvalSummary);
        return new AttemptOutcome(outcome, null);
    }

    private void rollbackFiles(String runId, String nodeId, WorkspaceSession session, int attemptNo) {
        if (session == null) {
            return;
        }
        List<String> restored = session.rollback();
        if (!restored.isEmpty()) {
            audit.record(runId, nodeId, AuditType.ROLLBACK, AuditService.SYSTEM,
                    "Restored " + restored.size() + " file(s) changed by attempt " + attemptNo + " to the last checkpoint",
                    Map.of("scope", "files", "paths", restored));
        }
    }

    /** Commits outputs, decisions and lineage in one transaction, then either finishes or parks the stage. */
    private Outcome commit(WorkflowDefinition workflow, NodeDefinition node, StageRun stage, StageAttempt attempt,
            String actor, AgentContext context, Map<String, String> outputs, ApprovalReason approvalReason,
            String approvalSummary) {
        String runId = stage.getRunId();
        Map<String, String> inputHashes = artifacts.committedHashes(runId, expectedInputs(workflow, runId, node.id()));
        String inputHashesJson = json.writeValueAsString(inputHashes);

        tx.executeWithoutResult(status -> {
            artifacts.commit(runId, node.id(), stage.getId(), attempt.getId(), actor, outputs);
            for (ProposedDecision proposed : context.decisions()) {
                Decision decision = new Decision();
                decision.setRunId(runId);
                decision.setNodeId(node.id());
                decision.setStageRunId(stage.getId());
                decision.setActor(actor);
                decision.setTitle(proposed.title());
                decision.setRationale(proposed.rationale());
                decision.setAlternatives(proposed.alternatives());
                decision.setInputHashes(inputHashesJson);
                decision.setCreatedAt(Instant.now());
                decisions.save(decision);
            }
            stage.setInputHashes(inputHashesJson);
            if (approvalReason != null) {
                ApprovalRequest request = new ApprovalRequest();
                request.setRunId(runId);
                request.setStageRunId(stage.getId());
                request.setNodeId(node.id());
                request.setReason(approvalReason);
                request.setSummary(approvalSummary);
                request.setStatus(ApprovalStatus.PENDING);
                request.setRequestedAt(Instant.now());
                approvals.save(request);
                stage.setStatus(StageStatus.AWAITING_APPROVAL);
            } else {
                stage.setStatus(StageStatus.SUCCEEDED);
                stage.setEndedAt(Instant.now());
            }
            stages.save(stage);
        });
        finishAttempt(attempt, AttemptStatus.SUCCEEDED, null, context.tokensUsed());

        audit.record(runId, node.id(), AuditType.ARTIFACTS_COMMITTED, actor,
                "Committed " + outputs.keySet() + " on attempt " + attempt.getAttemptNo(),
                Map.of("inputs", inputHashes));
        if (approvalReason != null) {
            audit.record(runId, node.id(), AuditType.APPROVAL_REQUESTED, AuditService.SYSTEM,
                    approvalReason + ": " + approvalSummary);
            return Outcome.AWAITING_APPROVAL;
        }
        audit.record(runId, node.id(), AuditType.STAGE_SUCCEEDED, AuditService.SYSTEM, "Stage succeeded");
        return Outcome.SUCCEEDED;
    }

    /**
     * Human decisions agents must respect: every decided approval that carries a comment, plus human edits of
     * artifacts. Built from the approval records, so decisions made before a stage re-runs are included.
     */
    private List<String> humanDecisions(String runId) {
        List<String> result = new ArrayList<>();
        for (ApprovalRequest approval : approvals.findByRunIdOrderByIdAsc(runId)) {
            if ((approval.getStatus() == ApprovalStatus.APPROVED || approval.getStatus() == ApprovalStatus.REJECTED)
                    && approval.getComment() != null && !approval.getComment().isBlank()) {
                result.add(approval.getStatus() + " " + approval.getReason() + " at " + approval.getNodeId() + " by "
                        + approval.getDecidedBy() + ": " + approval.getComment());
            }
        }
        decisions.findByRunIdOrderByIdAsc(runId).stream()
                .filter(d -> d.getActor() != null && d.getActor().startsWith("human:")
                        && (d.getTitle().startsWith("Revised") || d.getTitle().startsWith("Sent back")))
                .forEach(d -> result.add(d.getTitle() + " by " + d.getActor() + ": " + d.getRationale()));
        return result;
    }

    /**
     * Files of the stage's last rejected attempt, kept as a DISCARDED artifact so repair mode also works when
     * the stage is re-run later (for example after its upstream stage was sent back).
     */
    Map<String, String> lastProposal(String runId, String nodeId) {
        return artifacts.latest(runId, proposalArtifact(nodeId), ArtifactStatus.DISCARDED)
                .map(a -> {
                    Map<String, String> files = new LinkedHashMap<>();
                    json.readTree(a.getContent()).properties().forEach(e -> files.put(e.getKey(), e.getValue().asString()));
                    return files;
                })
                .orElse(Map.of());
    }

    /**
     * Repair starts from the previous version in the working tree, not just in the prompt: an agent that
     * returns only the files it fixes must not silently drop the rest of the change. Only paths inside the
     * stage's write scope are restored.
     */
    private static void restoreRepairBase(WorkspaceSession session, NodeDefinition node, Map<String, String> files) {
        List<java.nio.file.PathMatcher> scope = node.writes().stream()
                .map(glob -> java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + glob)).toList();
        files.forEach((path, content) -> {
            if (scope.stream().anyMatch(m -> m.matches(java.nio.file.Path.of(path)))) {
                try {
                    session.write(path, content);
                } catch (com.agentic.sdlc.workspace.WorkspaceAccessException ignored) {
                    // protected path; never part of a valid repair base
                }
            }
        });
    }

    static String proposalArtifact(String nodeId) {
        return nodeId + "_last_proposal";
    }

    /** Outputs of all ancestor stages, except stages that were SKIPPED and so produced nothing. */
    private List<String> expectedInputs(WorkflowDefinition workflow, String runId, String nodeId) {
        List<String> skipped = stages.findByRunIdOrderByIdAsc(runId).stream()
                .filter(s -> s.getStatus() == StageStatus.SKIPPED)
                .flatMap(s -> workflow.node(s.getNodeId()).outputs().stream())
                .toList();
        return workflow.inputArtifactsOf(nodeId).stream().filter(in -> !skipped.contains(in)).toList();
    }

    private List<AgentPlan> plansFor(NodeDefinition node) {
        List<AgentPlan> plans = new ArrayList<>();
        plans.add(new AgentPlan(node.agent(), node.maxRetries() + 1, false));
        if (node.fallbackAgent() != null) {
            plans.add(new AgentPlan(node.fallbackAgent(), node.fallbackAttempts(), true));
        }
        return plans;
    }

    /** Runs gates in declared order and stops at the first one that does not pass. */
    private GateVerdict runGates(String runId, List<String> gateNames, GateContext context) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        for (String gateName : gateNames) {
            GateResult result = gates.get(gateName).check(context);
            if (result.evidence() != null && !result.evidence().isEmpty()) {
                evidence.put(gateName, result.evidence());
                audit.record(runId, context.node().id(), AuditType.GATE_EVIDENCE, AuditService.SYSTEM,
                        gateName + " -> " + result.outcome(), result.evidence());
            }
            if (result.outcome() != GateResult.Outcome.PASS) {
                return new GateVerdict(result.outcome(), gateName + ": " + result.message(), evidence);
            }
        }
        return new GateVerdict(GateResult.Outcome.PASS, null, evidence);
    }

    private Result fail(StageRun stage, String reason) {
        stage.setStatus(StageStatus.FAILED);
        stage.setLastError(Text.truncate(reason, STORED_REASON_CHARS));
        stage.setEndedAt(Instant.now());
        stages.save(stage);
        audit.record(stage.getRunId(), stage.getNodeId(), AuditType.STAGE_FAILED, AuditService.SYSTEM,
                reason + " -> escalated to a human (retry the stage or revise its inputs)");
        return new Result(Outcome.FAILED, reason);
    }

    private void interrupt(StageRun stage, String reason) {
        stage.setStatus(StageStatus.PENDING);
        stages.save(stage);
        audit.record(stage.getRunId(), stage.getNodeId(), AuditType.STAGE_INTERRUPTED, AuditService.SYSTEM,
                "Stage returned to PENDING: " + reason);
    }

    private void finishAttempt(StageAttempt attempt, AttemptStatus status, String failure, long tokens) {
        attempt.setStatus(status);
        attempt.setFailureReason(Text.truncate(failure, STORED_REASON_CHARS));
        attempt.setTokensUsed(tokens);
        attempt.setEndedAt(Instant.now());
        attempts.save(attempt);
        meters.timer("sdlc.attempt.duration", "node", attempt.getNodeId(), "status", status.name())
                .record(Duration.between(attempt.getStartedAt(), attempt.getEndedAt()));
    }

    private static void pause(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
