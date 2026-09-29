package com.agentic.sdlc.state;

/**
 * PROPOSED: written by an attempt that is still being checked. COMMITTED: visible to downstream stages.
 * DISCARDED: the attempt failed its gates or policies. SUPERSEDED: replaced by a newer version.
 */
public enum ArtifactStatus {
    PROPOSED, COMMITTED, DISCARDED, SUPERSEDED
}
