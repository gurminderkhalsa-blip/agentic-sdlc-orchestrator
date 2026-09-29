package com.agentic.sdlc.workspace;

/** An agent tried to touch a path outside its sandbox. Reported back to the agent as feedback. */
public class WorkspaceAccessException extends RuntimeException {
    public WorkspaceAccessException(String message) {
        super(message);
    }
}
