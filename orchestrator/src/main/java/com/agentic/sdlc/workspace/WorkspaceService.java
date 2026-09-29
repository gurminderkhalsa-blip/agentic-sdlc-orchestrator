package com.agentic.sdlc.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.transport.RefSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.agentic.sdlc.common.ConflictException;
import com.agentic.sdlc.config.SdlcProperties;

import jakarta.annotation.PreDestroy;

/**
 * Owns the target repository and the per-run workspaces cloned from it.
 *
 * <p>Change control: agents only ever commit to the run's own branch in its own clone. The target
 * repository's {@code main} moves only when a human approves the release, and only by fast-forward, so a
 * release can never silently overwrite work published by another run.
 */
@Service
public class WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);
    private static final Set<String> TEMPLATE_SKIP = Set.of("build", ".gradle", ".sdlc", ".git");
    private static final PersonIdent ORCHESTRATOR = new PersonIdent("sdlc-orchestrator", "orchestrator@sdlc.local");

    private final SdlcProperties.Workspace config;
    private final BuildRunner builds;
    private final Path runsRoot;
    private final Path targetRepo;
    private final Map<String, Workspace> open = new ConcurrentHashMap<>();

    public WorkspaceService(SdlcProperties properties, BuildRunner builds) {
        this.config = properties.workspace();
        this.builds = builds;
        this.runsRoot = Path.of(config.root()).toAbsolutePath().normalize().resolve("runs");
        this.targetRepo = Path.of(config.targetRepo()).toAbsolutePath().normalize();
    }

    public Path targetRepo() {
        return targetRepo;
    }

    /** Clones the target repository (creating it from the template on first use) onto branch run/&lt;id&gt;. */
    public synchronized Workspace prepare(String runId) {
        ensureTargetRepo();
        Path dir = runsRoot.resolve(runId);
        String branch = branchFor(runId);
        try {
            Files.createDirectories(runsRoot);
            Git git = Git.cloneRepository().setURI(targetRepo.toUri().toString()).setDirectory(dir.toFile())
                    .setBranch("main").call();
            git.checkout().setCreateBranch(true).setName(branch).call();
            Workspace workspace = new Workspace(runId, branch, dir, git, builds, config.buildTimeout());
            open.put(runId, workspace);
            return workspace;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GitAPIException e) {
            throw new IllegalStateException("Could not clone " + targetRepo + ": " + e.getMessage(), e);
        }
    }

    /** The run's workspace, reopened from disk after a restart if needed. */
    public Optional<Workspace> find(String runId) {
        Workspace cached = open.get(runId);
        if (cached != null) {
            return Optional.of(cached);
        }
        Path dir = runsRoot.resolve(runId);
        if (!Files.isDirectory(dir.resolve(".git"))) {
            return Optional.empty();
        }
        try {
            Workspace workspace = new Workspace(runId, branchFor(runId), dir, Git.open(dir.toFile()), builds,
                    config.buildTimeout());
            open.put(runId, workspace);
            return Optional.of(workspace);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String targetHead() {
        try (Git target = Git.open(targetRepo.toFile())) {
            ObjectId head = target.getRepository().resolve("refs/heads/main");
            return head == null ? null : head.name();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Release: fast-forward the target repository's main branch to the run's branch. Fails with a conflict
     * if main has moved since the run started (another release landed); the run must then be rebased.
     */
    public synchronized String publish(String runId) {
        Workspace workspace = find(runId).orElseThrow(() -> new ConflictException("Run " + runId + " has no workspace"));
        String ref = "refs/heads/" + workspace.branch();
        try (Git target = Git.open(targetRepo.toFile())) {
            target.fetch().setRemote(workspace.root().toUri().toString())
                    .setRefSpecs(new RefSpec("+" + ref + ":" + ref)).call();
            MergeResult result = target.merge().include(target.getRepository().resolve(ref))
                    .setFastForward(MergeCommand.FastForwardMode.FF_ONLY).setCommit(true).call();
            if (!result.getMergeStatus().isSuccessful()) {
                throw new ConflictException("Cannot fast-forward main to " + workspace.branch() + " ("
                        + result.getMergeStatus() + "); main moved since this run started");
            }
            return target.getRepository().resolve("refs/heads/main").name();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GitAPIException e) {
            throw new IllegalStateException("Publish failed: " + e.getMessage(), e);
        }
    }

    private void ensureTargetRepo() {
        if (Files.isDirectory(targetRepo.resolve(".git"))) {
            return;
        }
        Path template = Path.of(config.template()).toAbsolutePath().normalize();
        if (!Files.isDirectory(template)) {
            throw new IllegalStateException("Service template not found at " + template);
        }
        try {
            copyTemplate(template, targetRepo);
            try (Git git = Git.init().setDirectory(targetRepo.toFile()).setInitialBranch("main").call()) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("Baseline: service template").setAuthor(ORCHESTRATOR)
                        .setCommitter(ORCHESTRATOR).setSign(false).call();
            }
            log.info("Created target repository {} from template {}", targetRepo, template);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GitAPIException e) {
            throw new IllegalStateException("Could not initialise target repository: " + e.getMessage(), e);
        }
    }

    private static void copyTemplate(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path source : walk.toList()) {
                Path relative = from.relativize(source);
                if (relative.getNameCount() > 0 && TEMPLATE_SKIP.contains(relative.getName(0).toString())) {
                    continue;
                }
                Path target = to.resolve(relative.toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static String branchFor(String runId) {
        return "run/" + runId.substring(0, Math.min(8, runId.length()));
    }

    @PreDestroy
    void closeAll() {
        open.values().forEach(Workspace::close);
        open.clear();
    }

    /** Test hook: files currently in the target repository. */
    List<String> targetFiles() {
        try (Stream<Path> walk = Files.walk(targetRepo)) {
            return walk.filter(Files::isRegularFile).map(p -> targetRepo.relativize(p).toString())
                    .filter(p -> !p.startsWith(".git")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
