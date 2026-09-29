package com.agentic.sdlc.llm;

import java.util.ArrayList;
import java.util.List;

/** A saved sequence of model calls, keyed by (stage, attempt), so a scenario can be replayed without a key. */
public record Recording(String name, List<Call> calls) {

    public Recording {
        calls = calls == null ? new ArrayList<>() : new ArrayList<>(calls);
    }

    /**
     * @param promptSha hash of the prompts at record time; a mismatch on replay is logged as prompt drift
     */
    public record Call(String nodeId, int attemptNo, String agent, String model, String promptSha, String content,
            long promptTokens, long completionTokens) {
    }
}
