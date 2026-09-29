package com.agentic.sdlc.policy;

import java.util.List;
import java.util.Map;

import com.agentic.sdlc.workspace.FileChange;

/**
 * Everything a stage attempt wants to commit: its artifacts, the workspace files it changed, and the paths
 * the workflow allows that stage to write.
 */
public record ProposedChange(String runId, String nodeId, Map<String, String> artifacts, List<FileChange> files,
        List<String> writeScopes) {

    public ProposedChange {
        files = files == null ? List.of() : List.copyOf(files);
        writeScopes = writeScopes == null ? List.of() : List.copyOf(writeScopes);
    }
}
