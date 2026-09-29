package com.agentic.sdlc.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordReplayTest {

    @TempDir
    Path dir;

    private static LlmRequest request(String node, int attempt, String user) {
        return new LlmRequest("run-1", "demo", node, attempt, "Agent", "model-x", "system", user);
    }

    @Test
    void recordedResponsesReplayByStageAndAttempt() {
        LlmClient live = req -> new LlmResponse("{\"answer\": \"" + req.nodeId() + req.attemptNo() + "\"}", "model-x", 10, 5);
        RecordingLlmClient recorder = new RecordingLlmClient(live, new RecordingStore(dir));
        recorder.complete(request("plan", 1, "prompt"));
        recorder.complete(request("plan", 2, "prompt with feedback"));

        ReplayLlmClient replay = new ReplayLlmClient(new RecordingStore(dir));

        LlmResponse second = replay.complete(request("plan", 2, "different feedback text"));
        assertThat(second.content()).isEqualTo("{\"answer\": \"plan2\"}");
        assertThat(second.totalTokens()).isEqualTo(15);
        assertThat(second.model()).endsWith("(replay)");
    }

    @Test
    void replayFailsClearlyWhenNothingWasRecorded() {
        ReplayLlmClient replay = new ReplayLlmClient(new RecordingStore(dir));

        assertThatThrownBy(() -> replay.complete(request("plan", 1, "p"))).isInstanceOf(LlmException.class)
                .hasMessageContaining("No recording named demo");
    }

    @Test
    void rejectsUnsafeRecordingNames() {
        RecordingStore store = new RecordingStore(dir);
        assertThatThrownBy(() -> store.load("../etc")).isInstanceOf(IllegalArgumentException.class);
    }
}
