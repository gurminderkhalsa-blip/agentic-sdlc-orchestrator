package com.agentic.sdlc.agent.llm;

import java.util.List;

import com.agentic.sdlc.agent.AgentContext;
import com.agentic.sdlc.workspace.WorkspaceSession;

/** Assembles the user prompt from labelled sections, keeping source-file context within a character budget. */
public class PromptBuilder {

    private final StringBuilder out = new StringBuilder();
    private int fileBudget;

    public PromptBuilder(int fileBudgetChars) {
        this.fileBudget = fileBudgetChars;
    }

    public PromptBuilder section(String title, String body) {
        if (body != null && !body.isBlank()) {
            out.append("## ").append(title).append("\n\n").append(body.strip()).append("\n\n");
        }
        return this;
    }

    /** Adds an upstream artifact if the run has it. */
    public PromptBuilder artifact(AgentContext context, String name) {
        context.readArtifact(name).ifPresent(content -> section("Artifact: " + name, content));
        return this;
    }

    public PromptBuilder humanDecisions(AgentContext context) {
        if (context.humanDecisions().isEmpty()) {
            return this;
        }
        return section("Binding decisions made by humans on this run",
                "These were decided by the accountable humans and override earlier assumptions. Do not contradict "
                        + "them; report a risk a human explicitly accepted as a risk, not as a blocking finding.\n- "
                        + String.join("\n- ", context.humanDecisions()));
    }

    public PromptBuilder feedback(AgentContext context) {
        if (!context.feedback().isEmpty()) {
            StringBuilder body = new StringBuilder("Earlier attempts at this stage were rejected. Fix every problem below; "
                    + "do not repeat the same mistake.\n");
            context.feedback().forEach(f -> body.append("\n- ").append(f.replace("\n", "\n  ")));
            section("Feedback you must address", body.toString());
        }
        return this;
    }

    /**
     * Repair mode: the previous attempt's files (rolled back after it failed) so the model fixes them
     * instead of regenerating everything and introducing new mistakes.
     */
    public PromptBuilder previousAttempt(AgentContext context) {
        if (context.previousAttemptFiles().isEmpty()) {
            return this;
        }
        StringBuilder body = new StringBuilder("Your previous attempt wrote the files below and was rejected for the "
                + "reasons under \"Feedback you must address\". Start from these files: make the smallest edits that "
                + "fix every reported problem, keep everything that already worked, and return the complete "
                + "corrected files.\n");
        context.previousAttemptFiles().forEach((path, content) ->
                body.append("\n### ").append(path).append("\n```\n").append(content).append("\n```\n"));
        return section("Previous attempt to repair", body.toString());
    }

    public PromptBuilder fileListing(WorkspaceSession workspace) {
        return section("Repository files", String.join("\n", workspace.listFiles()));
    }

    /** Includes the full text of files whose path starts with one of the prefixes, until the budget runs out. */
    public PromptBuilder files(WorkspaceSession workspace, List<String> prefixes) {
        StringBuilder body = new StringBuilder();
        for (String path : workspace.listFiles()) {
            if (prefixes.stream().noneMatch(path::startsWith)) {
                continue;
            }
            String content = workspace.read(path).orElse("");
            if (content.length() > fileBudget) {
                body.append("### ").append(path).append("\n(omitted: context budget exhausted)\n\n");
                continue;
            }
            fileBudget -= content.length();
            body.append("### ").append(path).append("\n```\n").append(content).append("\n```\n\n");
        }
        return section("Current file contents", body.toString());
    }

    public String build() {
        return out.toString();
    }
}
