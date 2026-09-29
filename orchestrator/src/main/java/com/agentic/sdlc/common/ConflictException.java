package com.agentic.sdlc.common;

/** The request is valid but not allowed in the current state (e.g. approving a stage that is not waiting). */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
