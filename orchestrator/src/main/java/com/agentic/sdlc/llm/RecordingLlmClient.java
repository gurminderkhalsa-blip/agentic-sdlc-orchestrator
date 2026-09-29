package com.agentic.sdlc.llm;

import com.agentic.sdlc.common.Hashing;

/** Calls the real model and saves every response, so the scenario can later be replayed exactly. */
public class RecordingLlmClient implements LlmClient {

    private final LlmClient delegate;
    private final RecordingStore store;

    public RecordingLlmClient(LlmClient delegate, RecordingStore store) {
        this.delegate = delegate;
        this.store = store;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        LlmResponse response = delegate.complete(request);
        String name = request.recording() == null ? request.runId() : request.recording();
        store.append(name, new Recording.Call(request.nodeId(), request.attemptNo(), request.agent(), response.model(),
                promptSha(request), response.content(), response.promptTokens(), response.completionTokens()));
        return response;
    }

    static String promptSha(LlmRequest request) {
        return Hashing.sha256(request.systemPrompt() + "\n---\n" + request.userPrompt());
    }
}
