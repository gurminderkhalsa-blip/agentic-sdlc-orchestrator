package com.agentic.sdlc.api;

import java.time.Instant;

import com.agentic.sdlc.state.Artifact;

/** Artifact metadata without the (possibly large) content. */
public record ArtifactSummary(Long id, String name, int version, String nodeId, String author, String status,
        String contentHash, int sizeChars, Instant createdAt) {

    static ArtifactSummary of(Artifact a) {
        return new ArtifactSummary(a.getId(), a.getName(), a.getVersion(), a.getNodeId(), a.getAuthor(),
                a.getStatus().name(), a.getContentHash(), a.getContent().length(), a.getCreatedAt());
    }
}
