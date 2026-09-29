package com.agentic.sdlc.workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Outcome of one Gradle invocation in a workspace, with helpers that turn raw output into agent feedback. */
public record BuildResult(List<String> tasks, int exitCode, boolean timedOut, long durationMs, String output) {

    private static final Pattern JAVA_ERROR = Pattern.compile(".*\\.java:\\d+: error:.*");

    public boolean succeeded() {
        return !timedOut && exitCode == 0;
    }

    /** javac errors with two lines of context each, capped so feedback stays readable. */
    public String compileErrors(int maxErrors) {
        List<String> lines = output.lines().toList();
        List<String> picked = new ArrayList<>();
        int errors = 0;
        for (int i = 0; i < lines.size() && errors < maxErrors; i++) {
            if (JAVA_ERROR.matcher(lines.get(i)).matches()) {
                errors++;
                for (int j = i; j < Math.min(i + 3, lines.size()); j++) {
                    picked.add(stripAbsolutePrefix(lines.get(j)));
                }
            }
        }
        return String.join("\n", picked);
    }

    public String tail(int maxLines) {
        List<String> lines = output.lines().toList();
        return String.join("\n", lines.subList(Math.max(0, lines.size() - maxLines), lines.size()));
    }

    /** Best summary of why the build failed: compiler errors if any, otherwise the end of the log. */
    public String failureSummary() {
        if (timedOut) {
            return "build timed out after " + durationMs / 1000 + "s";
        }
        String errors = compileErrors(25);
        return errors.isBlank() ? tail(40) : "Compilation errors:\n" + errors;
    }

    /** "/abs/path/to/workspace/src/X.java:3: error" -> "src/X.java:3: error", keeping indentation. */
    private static String stripAbsolutePrefix(String line) {
        String trimmed = line.stripLeading();
        int src = trimmed.indexOf("/src/");
        if (src > 0 && trimmed.startsWith("/")) {
            return line.substring(0, line.length() - trimmed.length()) + trimmed.substring(src + 1);
        }
        return line;
    }
}
