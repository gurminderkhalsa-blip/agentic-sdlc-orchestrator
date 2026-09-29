package com.agentic.sdlc.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves saved responses by (stage, attempt). Builds, tests and gates still run for real, so replay proves
 * the orchestration end to end; only the model's text is canned.
 */
public class ReplayLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(ReplayLlmClient.class);

    private final RecordingStore store;

    public ReplayLlmClient(RecordingStore store) {
        this.store = store;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        if (request.recording() == null) {
            throw new LlmException("Replay mode needs a recording name on the run");
        }
        Recording recording = store.load(request.recording())
                .orElseThrow(() -> new LlmException("No recording named " + request.recording()));
        Recording.Call call = recording.calls().stream()
                .filter(c -> c.nodeId().equals(request.nodeId()) && c.attemptNo() == request.attemptNo())
                .findFirst()
                .orElseThrow(() -> new LlmException("Recording " + request.recording() + " has no response for stage "
                        + request.nodeId() + " attempt " + request.attemptNo()));
        if (!call.promptSha().equals(RecordingLlmClient.promptSha(request))) {
            log.info("Prompt drift on {} attempt {} (feedback or workspace differs from the recording)",
                    request.nodeId(), request.attemptNo());
        }
        return new LlmResponse(call.content(), call.model() + " (replay)", call.promptTokens(), call.completionTokens());
    }
}
