package com.agentic.sdlc.workspace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The file-system view one agent attempt gets. Every write is recorded, so the attempt can be checked by
 * policies, then either checkpointed as one commit or rolled back file by file, without disturbing stages
 * running in parallel in the same workspace.
 */
public class WorkspaceSession {

    private final Workspace workspace;
    private final String nodeId;
    private final int attemptNo;
    private final String author;
    /** path -> content at the start of the attempt (null if the file did not exist). */
    private final Map<String, String> before = new LinkedHashMap<>();

    WorkspaceSession(Workspace workspace, String nodeId, int attemptNo, String author) {
        this.workspace = workspace;
        this.nodeId = nodeId;
        this.attemptNo = attemptNo;
        this.author = author;
    }

    public Optional<String> read(String path) {
        return workspace.read(path);
    }

    public List<String> listFiles() {
        return workspace.listFiles();
    }

    public void write(String path, String content) {
        String normalised = workspace.normalise(path, true);
        remember(normalised);
        workspace.write(normalised, content);
    }

    public void delete(String path) {
        String normalised = workspace.normalise(path, true);
        remember(normalised);
        workspace.delete(normalised);
    }

    /** Records the file's state before this attempt's first change to it (null = did not exist). */
    private void remember(String path) {
        if (!before.containsKey(path)) {
            before.put(path, workspace.read(path).orElse(null));
        }
    }

    /** Net changes of this attempt; files written back to their original content are left out. */
    public List<FileChange> changes() {
        List<FileChange> changes = new ArrayList<>();
        before.forEach((path, previous) -> {
            String now = workspace.read(path).orElse(null);
            if (!java.util.Objects.equals(now, previous)) {
                changes.add(new FileChange(path, now, previous));
            }
        });
        return changes;
    }

    public String diff() {
        return workspace.diff(changes().stream().map(FileChange::path).toList());
    }

    /**
     * Commits this attempt's files. {@code message} is the agent's Conventional Commit message (subject and
     * body); the orchestrator appends trailers that identify the stage, attempt, agent and run, which is how a
     * stage's commits are found again for rollback. Returns the commit id, or null if nothing changed.
     */
    public String checkpoint(String message) {
        List<String> paths = changes().stream().map(FileChange::path).toList();
        if (paths.isEmpty()) {
            return null;
        }
        String full = message.strip() + "\n\n" + Workspace.STAGE_TRAILER + nodeId + "\nAttempt: " + attemptNo
                + "\nAgent: " + author + "\nRun: " + workspace.runId();
        return workspace.commitPaths(paths, full, author);
    }

    /** Undoes every file this attempt touched. Returns the paths restored. */
    public List<String> rollback() {
        List<String> paths = List.copyOf(before.keySet());
        if (!paths.isEmpty()) {
            workspace.restorePaths(paths);
        }
        return paths;
    }

    public Workspace workspace() {
        return workspace;
    }
}
