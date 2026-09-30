package com.agentic.sdlc.agent.llm;

import java.util.ArrayList;
import java.util.List;

import com.agentic.sdlc.agent.Agent;
import com.agentic.sdlc.agent.AgentContext;
import com.agentic.sdlc.agent.AgentResult;
import com.agentic.sdlc.config.SdlcProperties;
import com.agentic.sdlc.llm.LlmClient;
import com.agentic.sdlc.llm.LlmRequest;
import com.agentic.sdlc.llm.LlmResponse;
import com.agentic.sdlc.workspace.WorkspaceAccessException;
import com.agentic.sdlc.workspace.WorkspaceSession;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Template for an LLM-backed agent: build a prompt from upstream artifacts, feedback and code; make exactly
 * one model call (so record/replay can key by stage and attempt); parse the JSON answer; apply it.
 * Anything malformed becomes an agent failure, which the engine retries with the reason as feedback.
 */
public abstract class LlmAgent implements Agent {

    protected static final int FILE_CONTEXT_CHARS = 150_000;

    protected final LlmClient llm;
    protected final PromptLibrary prompts;
    protected final ObjectMapper json;
    protected final SdlcProperties.Llm config;

    protected LlmAgent(LlmSupport support) {
        this.llm = support.llm();
        this.prompts = support.prompts();
        this.json = support.json();
        this.config = support.config();
    }

    /** Name of the prompt file under resources/prompts, without extension. */
    protected abstract String promptName();

    protected abstract void describeTask(AgentContext context, PromptBuilder prompt);

    protected abstract AgentResult apply(AgentContext context, JsonNode output);

    /** Extra system prompt fragments, e.g. the technology stack for code-writing agents. */
    protected List<String> systemFragments() {
        return List.of();
    }

    @Override
    public AgentResult execute(AgentContext context) {
        context.checkNotStopped();
        StringBuilder system = new StringBuilder(prompts.get(promptName()));
        for (String fragment : systemFragments()) {
            system.append("\n\n").append(prompts.get(fragment));
        }
        system.append("\n\n").append(prompts.get("_output_rules"));

        PromptBuilder prompt = new PromptBuilder(FILE_CONTEXT_CHARS)
                .section("Requirement", context.requirement())
                .section("Scenario hint", String.valueOf(context.scenario()));
        describeTask(context, prompt);
        prompt.humanDecisions(context);
        prompt.previousAttempt(context);
        prompt.feedback(context);

        String model = context.isFallback() ? config.effectiveFallbackModel() : config.model();
        LlmResponse response = llm.complete(new LlmRequest(context.runId(), context.recording(), context.node().id(),
                context.attemptNo(), name(), model, system.toString(), prompt.build()));
        context.recordTokens(response.totalTokens());
        context.checkNotStopped();

        JsonNode output;
        try {
            output = json.readTree(stripFences(response.content()));
        } catch (JacksonException e) {
            return AgentResult.failed("Your answer was not valid JSON (" + e.getOriginalMessage()
                    + "). Reply with a single JSON object only.");
        }
        if (!output.isObject()) {
            return AgentResult.failed("Your answer must be a JSON object, not " + output.getNodeType());
        }
        recordDecisions(context, output);
        return apply(context, output);
    }

    // ------------------------------------------------------------------ helpers for subclasses

    protected void recordDecisions(AgentContext context, JsonNode output) {
        for (JsonNode decision : output.path("decisions")) {
            context.recordDecision(decision.path("title").asString("(untitled)"),
                    decision.path("rationale").asString(""),
                    decision.path("alternatives").isMissingNode() ? null : decision.path("alternatives").toString());
        }
    }

    /** The output without its "decisions" (those are stored separately), pretty-printed. */
    protected String withoutDecisions(JsonNode output) {
        ObjectNode copy = ((ObjectNode) output).deepCopy();
        copy.remove("decisions");
        return json.writerWithDefaultPrettyPrinter().writeValueAsString(copy);
    }

    protected String pretty(JsonNode node) {
        return json.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    }

    protected WorkspaceSession workspace(AgentContext context) {
        return context.workspace().orElseThrow(() -> new IllegalStateException(
                name() + " needs a workspace; set 'workspace: true' on the workflow"));
    }

    /**
     * Writes {"files": [{"path", "content"}], "deletes": [path]} into the workspace.
     * Returns an error message for the agent if something was rejected, or null on success.
     */
    protected String applyFiles(AgentContext context, JsonNode output) {
        WorkspaceSession workspace = workspace(context);
        context.setCommitMessage(commitMessage(output));
        List<String> problems = new ArrayList<>();
        JsonNode files = output.path("files");
        if (!files.isArray() || files.isEmpty()) {
            return "The answer must contain a non-empty \"files\" array of {path, content}";
        }
        for (JsonNode file : files) {
            String path = file.path("path").asString("");
            JsonNode content = file.path("content");
            if (path.isBlank() || !content.isString()) {
                problems.add("each file needs a string path and string content");
                continue;
            }
            try {
                workspace.write(path, content.asString());
            } catch (WorkspaceAccessException e) {
                problems.add(e.getMessage());
            }
        }
        for (JsonNode path : output.path("deletes")) {
            try {
                workspace.delete(path.asString());
            } catch (WorkspaceAccessException e) {
                problems.add(e.getMessage());
            }
        }
        return problems.isEmpty() ? null : String.join("; ", problems);
    }

    /** A compact change-set artifact: which files changed, how, and the unified diff. */
    protected String changeSet(AgentContext context, JsonNode output) {
        WorkspaceSession workspace = workspace(context);
        ObjectNode changeSet = json.createObjectNode();
        changeSet.put("summary", output.path("summary").asString(""));
        var files = changeSet.putArray("files");
        workspace.changes().forEach(change -> files.addObject()
                .put("path", change.path())
                .put("action", change.isDelete() ? "delete" : change.existedBefore() ? "modify" : "create")
                .put("lines", change.isDelete() ? 0 : change.content().lines().count()));
        changeSet.put("diff", workspace.diff());
        return pretty(changeSet);
    }

    private static final java.util.regex.Pattern CONVENTIONAL_SUBJECT = java.util.regex.Pattern.compile(
            "^(feat|fix|test|docs|refactor|chore|perf|build)(\\([a-z0-9._-]+\\))?!?: \\S.{2,}$");

    /**
     * The agent's commit message if its subject follows Conventional Commits (subject trimmed to 72 chars);
     * otherwise a message derived from the agent's summary, so a weak message never costs a retry.
     */
    String commitMessage(JsonNode output) {
        String message = output.path("commitMessage").asString("").strip();
        String subject = message.lines().findFirst().orElse("");
        if (CONVENTIONAL_SUBJECT.matcher(subject).matches()) {
            String trimmed = subject.length() > 72 ? subject.substring(0, 72) : subject;
            return trimmed + message.substring(subject.length());
        }
        String summary = output.path("summary").asString("update").strip().replaceAll("\\s+", " ");
        String type = name().startsWith("Test") ? "test" : name().startsWith("Docs") ? "docs" : "feat";
        String derived = type + ": " + (summary.isEmpty() ? "update" : Character.toLowerCase(summary.charAt(0)) + summary.substring(1));
        return (derived.length() > 72 ? derived.substring(0, 72) : derived) + "\n\n" + summary;
    }

    static String stripFences(String content) {
        String trimmed = content.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return trimmed;
    }
}
