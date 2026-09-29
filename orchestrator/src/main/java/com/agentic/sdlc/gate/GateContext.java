package com.agentic.sdlc.gate;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import com.agentic.sdlc.workflow.NodeDefinition;
import com.agentic.sdlc.workspace.Workspace;

/**
 * What a gate can inspect. For exit gates, {@code artifact(...)} sees the attempt's proposed outputs layered
 * over committed ones; for entry gates it sees committed artifacts only.
 */
public record GateContext(String runId, NodeDefinition node, List<String> inputArtifacts,
        Function<String, Optional<String>> artifactReader, Optional<Workspace> workspace) {

    public Optional<String> artifact(String name) {
        return artifactReader.apply(name);
    }
}
