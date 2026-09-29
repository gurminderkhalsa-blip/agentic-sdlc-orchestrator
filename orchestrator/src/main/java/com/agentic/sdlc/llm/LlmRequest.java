package com.agentic.sdlc.llm;

/**
 * One model call. {@code nodeId}, {@code attemptNo} and {@code recording} identify the call for
 * record/replay; they are never sent to the provider.
 */
public record LlmRequest(String runId, String recording, String nodeId, int attemptNo, String agent,
        String model, String systemPrompt, String userPrompt) {
}
