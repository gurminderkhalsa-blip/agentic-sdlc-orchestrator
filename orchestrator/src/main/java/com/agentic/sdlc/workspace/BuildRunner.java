package com.agentic.sdlc.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

/**
 * Runs the workspace's Gradle wrapper as a child process. Generated code runs here, so the process gets a
 * scrubbed environment (no API keys or tokens), a working directory confined to the workspace, and a hard
 * timeout that kills the whole process tree.
 */
@Component
public class BuildRunner {

    private static final List<String> SECRET_MARKERS = List.of("KEY", "TOKEN", "SECRET", "PASSWORD", "CREDENTIAL");

    public BuildResult run(Path workspace, List<String> tasks, Duration timeout) {
        List<String> command = new ArrayList<>(List.of("./gradlew", "--console=plain"));
        command.addAll(tasks);
        long start = System.nanoTime();
        try {
            Path logDir = Files.createDirectories(workspace.resolve(".sdlc"));
            Path log = Files.createTempFile(logDir, "build-", ".log");
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(workspace.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile());
            scrub(builder.environment());
            Process process = builder.start();
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            String output = Files.readString(log, StandardCharsets.UTF_8);
            long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
            return new BuildResult(List.copyOf(tasks), finished ? process.exitValue() : -1, !finished, elapsed, output);
        } catch (IOException e) {
            return new BuildResult(List.copyOf(tasks), -1, false, 0, "Could not start Gradle: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new BuildResult(List.copyOf(tasks), -1, false, 0, "Build interrupted");
        }
    }

    static void scrub(Map<String, String> environment) {
        environment.keySet().removeIf(name -> SECRET_MARKERS.stream().anyMatch(name.toUpperCase()::contains));
    }
}
