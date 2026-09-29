package com.agentic.sdlc.state;


public enum StageStatus {
    PENDING, RUNNING, AWAITING_APPROVAL, SUCCEEDED, SKIPPED, FAILED;

    /** Downstream stages may start once every dependency is in one of these states. */
    public boolean satisfiesDependency() {
        return this == SUCCEEDED || this == SKIPPED;
    }
}
