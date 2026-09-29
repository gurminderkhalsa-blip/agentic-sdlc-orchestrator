package com.agentic.sdlc.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import com.agentic.sdlc.state.Scenario;
import com.agentic.sdlc.workspace.WorkspaceSession;
import com.agentic.sdlc.workflow.NodeDefinition;

/**
 * Everything an agent may see and do during one attempt. Writes are buffered here and only become visible
 * to other stages if the attempt passes its policies and exit gates.
 */
public class AgentContext {

    private final String runId;
    private final String requirement;
    private final Scenario scenario;
    private final NodeDefinition node;
    private final int attemptNo;
    private final List<String> feedback;
    private final Function<String, Optional<String>> committedReader;
    private final BooleanSupplier stopRequested;
    private final WorkspaceSession workspace;
    private final String recording;
    private final boolean fallback;
    private final Map<String, String> previousAttemptFiles;

    private final Map<String, String> staged = new LinkedHashMap<>();
    private final List<ProposedDecision> decisions = new java.util.ArrayList<>();
    private long tokensUsed;

    public AgentContext(String runId, String requirement, Scenario scenario, NodeDefinition node, int attemptNo,
            List<String> feedback, Function<String, Optional<String>> committedReader, BooleanSupplier stopRequested,
            WorkspaceSession workspace, String recording, boolean fallback) {
        this(runId, requirement, scenario, node, attemptNo, feedback, committedReader, stopRequested, workspace,
                recording, fallback, Map.of());
    }

    public AgentContext(String runId, String requirement, Scenario scenario, NodeDefinition node, int attemptNo,
            List<String> feedback, Function<String, Optional<String>> committedReader, BooleanSupplier stopRequested,
            WorkspaceSession workspace, String recording, boolean fallback, Map<String, String> previousAttemptFiles) {
        this.runId = runId;
        this.requirement = requirement;
        this.scenario = scenario;
        this.node = node;
        this.attemptNo = attemptNo;
        this.feedback = List.copyOf(feedback);
        this.committedReader = committedReader;
        this.stopRequested = stopRequested;
        this.workspace = workspace;
        this.recording = recording;
        this.fallback = fallback;
        this.previousAttemptFiles = Map.copyOf(previousAttemptFiles);
    }

    /**
     * Files the previous (rejected) attempt of this stage wrote, path to content. They were rolled back, so
     * this is the only way a retry can repair them instead of starting over.
     */
    public Map<String, String> previousAttemptFiles() {
        return previousAttemptFiles;
    }

    /** The run's code workspace, for workflows that change code. Writes are tracked per attempt. */
    public Optional<WorkspaceSession> workspace() {
        return Optional.ofNullable(workspace);
    }

    /** Recording name for LLM record/replay. */
    public String recording() {
        return recording;
    }

    /** True when this attempt is the fallback agent, after the primary agent was exhausted. */
    public boolean isFallback() {
        return fallback;
    }

    public String runId() {
        return runId;
    }

    public String requirement() {
        return requirement;
    }

    public Scenario scenario() {
        return scenario;
    }

    public NodeDefinition node() {
        return node;
    }

    public int attemptNo() {
        return attemptNo;
    }

    /** Reasons earlier attempts failed (gate messages, policy findings, human comments), oldest first. */
    public List<String> feedback() {
        return feedback;
    }

    /** Latest committed version of an artifact produced by an upstream stage. */
    public Optional<String> readArtifact(String name) {
        return committedReader.apply(name);
    }

    public void writeArtifact(String name, String content) {
        staged.put(name, content);
    }

    public void recordDecision(String title, String rationale, String alternatives) {
        decisions.add(new ProposedDecision(title, rationale, alternatives));
    }

    public void recordTokens(long tokens) {
        tokensUsed += tokens;
    }

    public void checkNotStopped() {
        if (stopRequested.getAsBoolean()) {
            throw new RunStoppedException("Run " + runId + " was stopped");
        }
    }

    public Map<String, String> stagedArtifacts() {
        return Collections.unmodifiableMap(staged);
    }

    public List<ProposedDecision> decisions() {
        return List.copyOf(decisions);
    }

    public long tokensUsed() {
        return tokensUsed;
    }
}
