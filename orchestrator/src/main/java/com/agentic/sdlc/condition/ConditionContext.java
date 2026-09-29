package com.agentic.sdlc.condition;

import java.util.Optional;
import java.util.function.Function;

import com.agentic.sdlc.state.Scenario;

public record ConditionContext(String runId, Scenario scenario, Function<String, Optional<String>> artifactReader) {

    public Optional<String> artifact(String name) {
        return artifactReader.apply(name);
    }
}
