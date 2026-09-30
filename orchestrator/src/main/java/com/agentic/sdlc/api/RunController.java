package com.agentic.sdlc.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.agentic.sdlc.audit.AuditService;
import com.agentic.sdlc.common.NotFoundException;
import com.agentic.sdlc.config.SdlcProperties;
import com.agentic.sdlc.deliverables.DeliverablesExporter;
import com.agentic.sdlc.engine.WorkflowEngine;
import com.agentic.sdlc.metrics.RunMetrics;
import com.agentic.sdlc.metrics.MetricsService;
import com.agentic.sdlc.state.ApprovalRequestRepository;
import com.agentic.sdlc.state.ApprovalStatus;
import com.agentic.sdlc.state.Artifact;
import com.agentic.sdlc.state.ArtifactRepository;
import com.agentic.sdlc.state.ArtifactStatus;
import com.agentic.sdlc.state.AuditEvent;
import com.agentic.sdlc.state.Decision;
import com.agentic.sdlc.state.DecisionRepository;
import com.agentic.sdlc.state.StageAttempt;
import com.agentic.sdlc.state.StageAttemptRepository;
import com.agentic.sdlc.state.StageRunRepository;
import com.agentic.sdlc.state.WorkflowRun;
import com.agentic.sdlc.state.WorkflowRunRepository;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final WorkflowEngine engine;
    private final WorkflowRunRepository runs;
    private final StageRunRepository stages;
    private final StageAttemptRepository attempts;
    private final ApprovalRequestRepository approvals;
    private final ArtifactRepository artifacts;
    private final DecisionRepository decisions;
    private final AuditService audit;
    private final MetricsService metrics;
    private final SdlcProperties properties;
    private final DeliverablesExporter exporter;

    public RunController(WorkflowEngine engine, WorkflowRunRepository runs, StageRunRepository stages,
            StageAttemptRepository attempts, ApprovalRequestRepository approvals, ArtifactRepository artifacts,
            DecisionRepository decisions, AuditService audit, MetricsService metrics, SdlcProperties properties,
            DeliverablesExporter exporter) {
        this.engine = engine;
        this.runs = runs;
        this.stages = stages;
        this.attempts = attempts;
        this.approvals = approvals;
        this.artifacts = artifacts;
        this.decisions = decisions;
        this.audit = audit;
        this.metrics = metrics;
        this.properties = properties;
        this.exporter = exporter;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RunView start(@Valid @RequestBody ApiRequests.StartRun request) {
        String workflow = request.workflow() == null ? properties.defaultWorkflow() : request.workflow();
        WorkflowRun run = engine.start(workflow, request.requirement(), request.scenario(), request.actor(),
                request.recording());
        return view(run.getId());
    }

    @GetMapping
    public List<WorkflowRun> list() {
        return runs.findAllByOrderByCreatedAtDesc();
    }

    @GetMapping("/{runId}")
    public RunView get(@PathVariable String runId) {
        return view(runId);
    }

    @PostMapping("/{runId}/stop")
    public RunView stop(@PathVariable String runId, @Valid @RequestBody ApiRequests.Stop request) {
        engine.stop(runId, AuditService.humanActor(request.actor()), request.reason());
        return view(runId);
    }

    @PostMapping("/{runId}/resume")
    public RunView resume(@PathVariable String runId, @Valid @RequestBody ApiRequests.Act request) {
        engine.resume(runId, AuditService.humanActor(request.actor()));
        return view(runId);
    }

    /** Re-run a FAILED stage, or send a SUCCEEDED one back with feedback (downstream is re-planned). */
    @PostMapping("/{runId}/stages/{nodeId}/rerun")
    public RunView rerunStage(@PathVariable String runId, @PathVariable String nodeId,
            @Valid @RequestBody ApiRequests.Rerun request) {
        engine.rerunStage(runId, nodeId, AuditService.humanActor(request.actor()), request.feedback());
        return view(runId);
    }

    @GetMapping("/{runId}/attempts")
    public List<StageAttempt> attempts(@PathVariable String runId) {
        requireRun(runId);
        return attempts.findByRunIdOrderByIdAsc(runId);
    }

    @GetMapping("/{runId}/artifacts")
    public List<ArtifactSummary> artifacts(@PathVariable String runId) {
        requireRun(runId);
        return artifacts.findByRunIdOrderByIdAsc(runId).stream().map(ArtifactSummary::of).toList();
    }

    /** Latest committed version. */
    @GetMapping("/{runId}/artifacts/{name}")
    public Artifact artifact(@PathVariable String runId, @PathVariable String name) {
        return artifacts.findFirstByRunIdAndNameAndStatusOrderByVersionDesc(runId, name, ArtifactStatus.COMMITTED)
                .orElseThrow(() -> new NotFoundException("No committed artifact " + name + " in run " + runId));
    }

    @GetMapping("/{runId}/artifacts/{name}/versions")
    public List<Artifact> artifactVersions(@PathVariable String runId, @PathVariable String name) {
        requireRun(runId);
        return artifacts.findByRunIdAndNameOrderByVersionAsc(runId, name);
    }

    /** Human edit of an artifact; downstream stages are re-planned. */
    @PutMapping("/{runId}/artifacts/{name}")
    public RunView revise(@PathVariable String runId, @PathVariable String name,
            @Valid @RequestBody ApiRequests.Revise request) {
        engine.reviseArtifact(runId, name, request.content(), AuditService.humanActor(request.actor()),
                request.reason());
        return view(runId);
    }

    /** Writes every artifact of the run into deliverables/&lt;name&gt;/ (name defaults to the recording). */
    @PostMapping("/{runId}/export")
    public DeliverablesExporter.ExportResult export(@PathVariable String runId,
            @RequestBody(required = false) ApiRequests.Export request) {
        return exporter.export(runId, request == null ? null : request.name());
    }

    @GetMapping("/{runId}/decisions")
    public List<Decision> decisions(@PathVariable String runId) {
        requireRun(runId);
        return decisions.findByRunIdOrderByIdAsc(runId);
    }

    @GetMapping("/{runId}/audit")
    public List<AuditEvent> audit(@PathVariable String runId) {
        requireRun(runId);
        return audit.trail(runId);
    }

    @GetMapping("/{runId}/metrics")
    public RunMetrics metrics(@PathVariable String runId) {
        return metrics.forRun(runId);
    }

    private RunView view(String runId) {
        WorkflowRun run = requireRun(runId);
        return new RunView(run, stages.findByRunIdOrderByIdAsc(runId),
                approvals.findByRunIdOrderByIdAsc(runId).stream()
                        .filter(a -> a.getStatus() == ApprovalStatus.PENDING).toList());
    }

    private WorkflowRun requireRun(String runId) {
        return runs.findById(runId).orElseThrow(() -> new NotFoundException("Unknown run " + runId));
    }
}
