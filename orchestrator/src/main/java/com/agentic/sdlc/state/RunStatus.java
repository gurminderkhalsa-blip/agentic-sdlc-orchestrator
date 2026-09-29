package com.agentic.sdlc.state;


public enum RunStatus {
    RUNNING, AWAITING_APPROVAL, SUCCEEDED, FAILED, STOPPED;

    public boolean isFinished() {
        return this == SUCCEEDED || this == FAILED;
    }
}
