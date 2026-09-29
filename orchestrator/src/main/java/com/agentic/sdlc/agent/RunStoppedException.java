package com.agentic.sdlc.agent;

/** Thrown from {@link AgentContext#checkNotStopped()} so a long-running agent can end promptly on a safe-stop. */
public class RunStoppedException extends RuntimeException {
    public RunStoppedException(String reason) {
        super(reason);
    }
}
