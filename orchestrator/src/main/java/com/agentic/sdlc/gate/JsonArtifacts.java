package com.agentic.sdlc.gate;

import java.util.Optional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class JsonArtifacts {

    private JsonArtifacts() {
    }

    static Optional<JsonNode> read(ObjectMapper json, GateContext context, String name) {
        return context.artifact(name).flatMap(content -> {
            try {
                return Optional.of(json.readTree(content));
            } catch (JacksonException e) {
                return Optional.empty();
            }
        });
    }
}
