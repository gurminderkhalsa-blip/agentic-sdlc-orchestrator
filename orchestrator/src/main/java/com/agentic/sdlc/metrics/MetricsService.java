package com.agentic.sdlc.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.agentic.sdlc.audit.AuditType;
import com.agentic.sdlc.common.NotFoundException;
import com.agentic.sdlc.state.ApprovalRequest;
import com.agentic.sdlc.state.ApprovalRequestRepository;
import com.agentic.sdlc.state.ApprovalStatus;
import com.agentic.sdlc.state.AttemptStatus;
import com.agentic.sdlc.state.AuditEventRepository;
import com.agentic.sdlc.state.RunStatus;
import com.agentic.sdlc.state.StageAttempt;
import com.agentic.sdlc.state.StageAttemptRepository;
import com.agentic.sdlc.state.StageRun;
import com.agentic.sdlc.state.StageRunRepository;
import com.agentic.sdlc.state.WorkflowRun;
import com.agentic.sdlc.state.WorkflowRunRepository;

/** Computes reliability metrics from the durable state, so they survive restarts and can be re-derived. */
@Service
public class MetricsService {

    private final WorkflowRunRepository runs;
    private final StageRunRepository stages;
    private final StageAttemptRepository attempts;
    private final ApprovalRequestRepository approvals;
    private final AuditEventRepository audit;

    public MetricsService(WorkflowRunRepository runs, StageRunRepository stages, StageAttemptRepository attempts,
            ApprovalRequestRepository approvals, AuditEventRepository audit) {
        this.runs = runs;
        this.stages = stages;
        this.attempts = attempts;
        this.approvals = approvals;
        this.audit = audit;
    }

    public RunMetrics forRun(String runId) {
        WorkflowRun run = runs.findById(runId).orElseThrow(() -> new NotFoundException("Unknown run " + runId));
        List<StageAttempt> runAttempts = attempts.findByRunIdOrderByIdAsc(runId);
        List<ApprovalRequest> runApprovals = approvals.findByRunIdOrderByIdAsc(runId);
        Map<Long, List<StageAttempt>> byStage = groupByStage(runAttempts);

        List<RunMetrics.StageMetrics> stageMetrics = new ArrayList<>();
        for (StageRun stage : stages.findByRunIdOrderByIdAsc(runId)) {
            Long duration = stage.getStartedAt() == null || stage.getEndedAt() == null ? null
                    : Duration.between(stage.getStartedAt(), stage.getEndedAt()).toMillis();
            stageMetrics.add(new RunMetrics.StageMetrics(stage.getNodeId(), stage.getStatus().name(),
                    stage.getAttemptCount(), duration));
        }
        List<Long> recoveries = recoveryTimes(byStage);
        return new RunMetrics(
                runId,
                run.getStatus().name(),
                millisBetween(run.getCreatedAt(), run.getEndedAt()),
                runAttempts.stream().mapToLong(a -> millisBetween(a.getStartedAt(), a.getEndedAt())).sum(),
                runApprovals.stream().mapToLong(a -> millisBetween(a.getRequestedAt(), a.getDecidedAt())).sum(),
                runAttempts.size(),
                (int) runAttempts.stream().filter(a -> a.getStatus() == AttemptStatus.FAILED).count(),
                runAttempts.size() - byStage.size(),
                audit.countByRunIdAndType(runId, AuditType.ROLLBACK),
                runApprovals.size(),
                (int) runApprovals.stream().filter(a -> a.getStatus() == ApprovalStatus.REJECTED).count(),
                runAttempts.stream().mapToLong(StageAttempt::getTokensUsed).sum(),
                average(recoveries),
                stageMetrics);
    }

    public ReliabilityMetrics overall() {
        List<WorkflowRun> allRuns = runs.findAll();
        List<StageAttempt> allAttempts = attempts.findAll();
        Map<Long, List<StageAttempt>> byStage = groupByStage(allAttempts);

        Map<String, Long> byStatus = allRuns.stream()
                .collect(Collectors.groupingBy(r -> r.getStatus().name(), TreeMap::new, Collectors.counting()));
        long succeeded = byStatus.getOrDefault(RunStatus.SUCCEEDED.name(), 0L);
        long finished = succeeded + byStatus.getOrDefault(RunStatus.FAILED.name(), 0L);
        long firstPass = byStage.values().stream()
                .filter(list -> list.get(0).getStatus() == AttemptStatus.SUCCEEDED).count();
        long retries = allAttempts.size() - byStage.size();
        long rollbacks = audit.countByType(AuditType.ROLLBACK);
        List<Long> latencies = allRuns.stream().filter(r -> r.getStatus() == RunStatus.SUCCEEDED)
                .map(r -> millisBetween(r.getCreatedAt(), r.getEndedAt())).toList();

        return new ReliabilityMetrics(
                allRuns.size(),
                byStatus,
                ratio(succeeded, finished),
                ratio(firstPass, byStage.size()),
                ratio(retries, allAttempts.size()),
                ratio(rollbacks, allRuns.size()),
                average(recoveryTimes(byStage)),
                average(latencies),
                allAttempts.size(),
                rollbacks);
    }

    /** For each stage: time from the first failure in a streak to the next success. */
    static List<Long> recoveryTimes(Map<Long, List<StageAttempt>> attemptsByStage) {
        List<Long> recoveries = new ArrayList<>();
        for (List<StageAttempt> stageAttempts : attemptsByStage.values()) {
            Instant failedAt = null;
            for (StageAttempt attempt : stageAttempts) {
                if (attempt.getStatus() == AttemptStatus.FAILED && failedAt == null) {
                    failedAt = attempt.getEndedAt();
                } else if (attempt.getStatus() == AttemptStatus.SUCCEEDED && failedAt != null) {
                    recoveries.add(Duration.between(failedAt, attempt.getEndedAt()).toMillis());
                    failedAt = null;
                }
            }
        }
        return recoveries;
    }

    private static Map<Long, List<StageAttempt>> groupByStage(List<StageAttempt> attempts) {
        Map<Long, List<StageAttempt>> byStage = new LinkedHashMap<>();
        attempts.forEach(a -> byStage.computeIfAbsent(a.getStageRunId(), k -> new ArrayList<>()).add(a));
        return byStage;
    }

    private static long millisBetween(Instant start, Instant end) {
        if (start == null) {
            return 0;
        }
        return Duration.between(start, end == null ? Instant.now() : end).toMillis();
    }

    private static Double ratio(long part, long whole) {
        return whole == 0 ? null : Math.round(1000.0 * part / whole) / 1000.0;
    }

    private static Long average(List<Long> values) {
        return values.isEmpty() ? null : Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0));
    }
}
