package com.agentic.sdlc.agent;

/** What an agent reports back. A failure is retried like a failed gate, with {@code summary} as feedback. */
public record AgentResult(boolean success, String summary) {

    public static AgentResult ok(String summary) {
        return new AgentResult(true, summary);
    }

    public static AgentResult failed(String summary) {
        return new AgentResult(false, summary);
    }
}
