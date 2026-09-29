package com.agentic.sdlc.workspace;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RevertCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;

/**
 * One run's private git working copy of the target repository, on its own branch. Every successful stage
 * attempt becomes a commit tagged with the stage id, which makes rollback a plain {@code git revert} and
 * keeps a reviewable history of what each agent changed.
 */
public class Workspace {

    /** Paths no agent may ever touch: git internals, the build wrapper and orchestrator scratch files. */
    private static final List<String> PROTECTED_PREFIXES = List.of(".git/", "gradle/", ".sdlc/", "build/", ".gradle/");
    private static final Set<String> PROTECTED_FILES = Set.of("gradlew", "gradlew.bat", ".gitignore");
    private static final Set<String> HIDDEN_DIRS = Set.of(".git", "build", ".gradle", ".sdlc", "gradle", "bin", "out");
    private static final Pattern REVERTS = Pattern.compile("This reverts commit ([0-9a-f]{40})");

    private final String runId;
    private final String branch;
    private final Path root;
    private final Git git;
    private final BuildRunner builds;
    private final Duration buildTimeout;
    private final ReentrantLock gitLock = new ReentrantLock();
    private final ReentrantLock buildLock = new ReentrantLock();

    Workspace(String runId, String branch, Path root, Git git, BuildRunner builds, Duration buildTimeout) {
        this.runId = runId;
        this.branch = branch;
        this.root = root;
        this.git = git;
        this.builds = builds;
        this.buildTimeout = buildTimeout;
    }

    public String runId() {
        return runId;
    }

    public String branch() {
        return branch;
    }

    public Path root() {
        return root;
    }

    /** Starts tracking one agent attempt's changes so they can be checkpointed or rolled back together. */
    public WorkspaceSession session(String nodeId, int attemptNo, String author) {
        return new WorkspaceSession(this, nodeId, attemptNo, author);
    }

    // ------------------------------------------------------------------ files

    public Optional<String> read(String relativePath) {
        Path file = resolve(relativePath, false);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Project files an agent may look at, relative to the root, with '/' separators. */
    public List<String> listFiles() {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(p -> HIDDEN_DIRS.stream().noneMatch(d -> p.startsWith(d + "/")))
                    .filter(p -> !PROTECTED_FILES.contains(p))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void write(String relativePath, String content) {
        Path file = resolve(relativePath, true);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void delete(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath, true));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Sandbox check: the path must be relative, stay inside the workspace after normalisation, and avoid
     * protected locations when writing.
     */
    String normalise(String relativePath, boolean forWrite) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new WorkspaceAccessException("Empty path");
        }
        String cleaned = relativePath.replace('\\', '/').trim();
        if (cleaned.startsWith("/") || cleaned.matches("^[A-Za-z]:.*")) {
            throw new WorkspaceAccessException("Absolute paths are not allowed: " + relativePath);
        }
        Path resolved = root.resolve(cleaned).normalize();
        if (!resolved.startsWith(root)) {
            throw new WorkspaceAccessException("Path escapes the workspace: " + relativePath);
        }
        String normalised = root.relativize(resolved).toString().replace('\\', '/');
        if (forWrite && (PROTECTED_FILES.contains(normalised)
                || PROTECTED_PREFIXES.stream().anyMatch(normalised::startsWith))) {
            throw new WorkspaceAccessException("Protected path: " + normalised);
        }
        return normalised;
    }

    private Path resolve(String relativePath, boolean forWrite) {
        return root.resolve(normalise(relativePath, forWrite));
    }

    // ------------------------------------------------------------------ git

    public String headCommit() {
        try {
            ObjectId head = git.getRepository().resolve("HEAD");
            return head == null ? null : head.name();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Content of a file at the last checkpoint (HEAD), or empty if it did not exist there. */
    public Optional<String> readAtHead(String relativePath) {
        String path = normalise(relativePath, false);
        gitLock.lock();
        try (RevWalk walk = new RevWalk(git.getRepository())) {
            ObjectId head = git.getRepository().resolve("HEAD");
            if (head == null) {
                return Optional.empty();
            }
            RevCommit commit = walk.parseCommit(head);
            try (TreeWalk tree = TreeWalk.forPath(git.getRepository(), path, commit.getTree())) {
                if (tree == null) {
                    return Optional.empty();
                }
                byte[] bytes = git.getRepository().open(tree.getObjectId(0)).getBytes();
                return Optional.of(new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            gitLock.unlock();
        }
    }

    /** Commits exactly these paths (other stages' uncommitted work is untouched). Returns null if nothing changed. */
    String commitPaths(Collection<String> paths, String message, String author) {
        gitLock.lock();
        try {
            for (String path : paths) {
                if (Files.exists(root.resolve(path))) {
                    git.add().addFilepattern(path).call();
                } else if (readAtHeadUnlocked(path)) {
                    git.rm().setCached(true).addFilepattern(path).call();
                }
            }
            Status status = git.status().call();
            if (status.getAdded().isEmpty() && status.getChanged().isEmpty() && status.getRemoved().isEmpty()) {
                return null;
            }
            PersonIdent ident = new PersonIdent(author, author.replace(':', '.') + "@sdlc.local");
            RevCommit commit = git.commit().setMessage(message).setAuthor(ident).setCommitter(ident)
                    .setSign(false).call();
            return commit.name();
        } catch (GitAPIException e) {
            throw new IllegalStateException("git commit failed: " + e.getMessage(), e);
        } finally {
            gitLock.unlock();
        }
    }

    /** Puts files back to their last checkpoint; files created since then are deleted. */
    void restorePaths(Collection<String> paths) {
        gitLock.lock();
        try {
            List<String> tracked = new ArrayList<>();
            for (String path : paths) {
                if (readAtHeadUnlocked(path)) {
                    tracked.add(path);
                } else {
                    Files.deleteIfExists(root.resolve(path));
                }
            }
            if (!tracked.isEmpty()) {
                var checkout = git.checkout().setStartPoint("HEAD");
                tracked.forEach(checkout::addPath);
                checkout.call();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GitAPIException e) {
            throw new IllegalStateException("git restore failed: " + e.getMessage(), e);
        } finally {
            gitLock.unlock();
        }
    }

    /**
     * Reverts every checkpoint commit the stage made in this run that is not already reverted, newest first.
     * Only commits after {@code baselineCommit} are considered: history inherited from main (for example the
     * release this run builds on) belongs to earlier runs and is never touched.
     */
    public List<String> revertStage(String nodeId, String baselineCommit) {
        if (baselineCommit == null) {
            throw new IllegalArgumentException("baselineCommit is required to scope the revert to this run");
        }
        gitLock.lock();
        try {
            List<RevCommit> stageCommits = activeStageCommits(nodeId, baselineCommit);
            List<String> reverted = new ArrayList<>();
            for (RevCommit commit : stageCommits) {
                RevertCommand revert = git.revert().include(commit);
                RevCommit result = revert.call();
                if (result == null) {
                    throw new IllegalStateException("Could not revert " + commit.name() + " cleanly: "
                            + revert.getFailingResult());
                }
                reverted.add(commit.name());
            }
            return reverted;
        } catch (GitAPIException e) {
            throw new IllegalStateException("git revert failed: " + e.getMessage(), e);
        } finally {
            gitLock.unlock();
        }
    }

    /**
     * Current content of every file the stage's (non-reverted) checkpoints in this run touched. Captured before
     * a stage is sent back, so the rework starts from the previous version instead of from scratch.
     */
    public Map<String, String> stageFiles(String nodeId, String baselineCommit) {
        Set<String> paths = new LinkedHashSet<>();
        gitLock.lock();
        try (RevWalk walk = new RevWalk(git.getRepository());
                DiffFormatter diffs = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            diffs.setRepository(git.getRepository());
            for (RevCommit commit : activeStageCommits(nodeId, baselineCommit)) {
                RevCommit parsed = walk.parseCommit(commit);
                RevCommit parent = walk.parseCommit(parsed.getParent(0));
                for (DiffEntry entry : diffs.scan(parent.getTree(), parsed.getTree())) {
                    paths.add(entry.getChangeType() == DiffEntry.ChangeType.DELETE ? entry.getOldPath() : entry.getNewPath());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            gitLock.unlock();
        }
        Map<String, String> files = new LinkedHashMap<>();
        for (String path : paths) {
            read(path).ifPresent(content -> files.put(path, content));
        }
        return files;
    }

    /** Stage checkpoint commits after the baseline that have not been reverted, newest first. */
    private List<RevCommit> activeStageCommits(String nodeId, String baselineCommit) {
        try {
            Set<String> alreadyReverted = new HashSet<>();
            List<RevCommit> stageCommits = new ArrayList<>();
            ObjectId since = git.getRepository().resolve(baselineCommit);
            ObjectId head = git.getRepository().resolve("HEAD");
            for (RevCommit commit : git.log().addRange(since, head).call()) {
                Matcher m = REVERTS.matcher(commit.getFullMessage());
                if (m.find()) {
                    alreadyReverted.add(m.group(1));
                } else if (commit.getFullMessage().startsWith("[" + nodeId + "]")) {
                    stageCommits.add(commit);
                }
            }
            stageCommits.removeIf(c -> alreadyReverted.contains(c.name()));
            return stageCommits;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GitAPIException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Unified diff of the given paths between the last checkpoint and the working tree. */
    public String diff(Collection<String> paths) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DiffFormatter formatter = new DiffFormatter(out)) {
            for (String path : paths) {
                byte[] before = readAtHead(path).orElse("").getBytes(StandardCharsets.UTF_8);
                byte[] after = read(path).orElse("").getBytes(StandardCharsets.UTF_8);
                RawText a = new RawText(before);
                RawText b = new RawText(after);
                EditList edits = new HistogramDiff().diff(RawTextComparator.DEFAULT, a, b);
                if (edits.isEmpty()) {
                    continue;
                }
                out.write(("--- a/" + path + "\n+++ b/" + path + "\n").getBytes(StandardCharsets.UTF_8));
                formatter.format(edits, a, b);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Commit subjects on this run's branch, newest first (for reports and the release notes). */
    public List<String> history(int max) {
        gitLock.lock();
        try {
            List<String> lines = new ArrayList<>();
            for (RevCommit commit : git.log().setMaxCount(max).call()) {
                lines.add(commit.getName().substring(0, 10) + " " + commit.getShortMessage());
            }
            return lines;
        } catch (GitAPIException e) {
            throw new IllegalStateException(e);
        } finally {
            gitLock.unlock();
        }
    }

    private boolean readAtHeadUnlocked(String path) {
        try (RevWalk walk = new RevWalk(git.getRepository())) {
            ObjectId head = git.getRepository().resolve("HEAD");
            if (head == null) {
                return false;
            }
            try (TreeWalk tree = TreeWalk.forPath(git.getRepository(), path, walk.parseCommit(head).getTree())) {
                return tree != null;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ builds

    /** One Gradle build at a time per workspace; parallel stages queue here rather than corrupt build/. */
    public BuildResult build(List<String> tasks) {
        buildLock.lock();
        try {
            return builds.run(root, tasks, buildTimeout);
        } finally {
            buildLock.unlock();
        }
    }

    Git git() {
        return git;
    }

    void close() {
        git.close();
    }
}
