package com.agentic.sdlc.engine;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.agentic.sdlc.common.Hashing;
import com.agentic.sdlc.state.Artifact;
import com.agentic.sdlc.state.ArtifactRepository;
import com.agentic.sdlc.state.ArtifactStatus;

/** Versioned artifact storage. Only COMMITTED versions are ever visible to agents, gates and conditions. */
@Component
public class ArtifactStore {

    private final ArtifactRepository artifacts;

    public ArtifactStore(ArtifactRepository artifacts) {
        this.artifacts = artifacts;
    }

    public Optional<String> readCommitted(String runId, String name) {
        return committed(runId, name).map(Artifact::getContent);
    }

    public Optional<Artifact> committed(String runId, String name) {
        return artifacts.findFirstByRunIdAndNameAndStatusOrderByVersionDesc(runId, name, ArtifactStatus.COMMITTED);
    }

    /** Latest version with the given status, e.g. the last DISCARDED proposal of a stage. */
    public Optional<Artifact> latest(String runId, String name, ArtifactStatus status) {
        return artifacts.findFirstByRunIdAndNameAndStatusOrderByVersionDesc(runId, name, status);
    }

    /** Current hash of each named artifact; missing artifacts are left out. */
    public Map<String, String> committedHashes(String runId, List<String> names) {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String name : names) {
            committed(runId, name).ifPresent(a -> hashes.put(name, a.getContentHash()));
        }
        return hashes;
    }

    /** Makes an attempt's outputs visible, superseding any earlier committed version of the same artifact. */
    @Transactional
    public List<Artifact> commit(String runId, String nodeId, Long stageRunId, Long attemptId, String author,
            Map<String, String> staged) {
        return staged.entrySet().stream().map(e -> {
            committed(runId, e.getKey()).ifPresent(previous -> {
                previous.setStatus(ArtifactStatus.SUPERSEDED);
                artifacts.save(previous);
            });
            return save(runId, e.getKey(), e.getValue(), nodeId, stageRunId, attemptId, author, ArtifactStatus.COMMITTED);
        }).toList();
    }

    /** Keeps a failed attempt's outputs for the audit trail without ever exposing them downstream. */
    @Transactional
    public void discard(String runId, String nodeId, Long stageRunId, Long attemptId, String author,
            Map<String, String> staged) {
        staged.forEach((name, content) ->
                save(runId, name, content, nodeId, stageRunId, attemptId, author, ArtifactStatus.DISCARDED));
    }

    /** A human edit of a committed artifact. Returns the new version. */
    @Transactional
    public Artifact revise(Artifact current, String content, String author) {
        current.setStatus(ArtifactStatus.SUPERSEDED);
        artifacts.save(current);
        return save(current.getRunId(), current.getName(), content, current.getNodeId(), current.getStageRunId(),
                null, author, ArtifactStatus.COMMITTED);
    }

    /** Withdraws everything a stage committed; used when a human rejects it or re-planning invalidates it. */
    @Transactional
    public int supersedeStageOutputs(String runId, String nodeId) {
        List<Artifact> current = artifacts.findByRunIdAndNodeIdAndStatus(runId, nodeId, ArtifactStatus.COMMITTED);
        current.forEach(a -> a.setStatus(ArtifactStatus.SUPERSEDED));
        artifacts.saveAll(current);
        return current.size();
    }

    private Artifact save(String runId, String name, String content, String nodeId, Long stageRunId, Long attemptId,
            String author, ArtifactStatus status) {
        Artifact artifact = new Artifact();
        artifact.setRunId(runId);
        artifact.setName(name);
        artifact.setVersion(artifacts.maxVersion(runId, name) + 1);
        artifact.setNodeId(nodeId);
        artifact.setStageRunId(stageRunId);
        artifact.setAttemptId(attemptId);
        artifact.setAuthor(author);
        artifact.setStatus(status);
        artifact.setContent(content);
        artifact.setContentHash(Hashing.sha256(content));
        artifact.setCreatedAt(Instant.now());
        return artifacts.save(artifact);
    }
}
