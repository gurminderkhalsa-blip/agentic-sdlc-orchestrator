package com.agentic.sdlc.agent.llm;

import java.util.List;

import com.agentic.sdlc.agent.AgentContext;
import com.agentic.sdlc.agent.AgentResult;

import tools.jackson.databind.JsonNode;

/** Writes release notes and the final engineering summary (plan, artifacts, risks, assumptions, limits). */
public class ReleaseAgent extends LlmAgent {

    public ReleaseAgent(LlmSupport support) {
        super(support);
    }

    @Override
    public String name() {
        return "ReleaseAgent";
    }

    @Override
    protected String promptName() {
        return "release";
    }

    @Override
    protected void describeTask(AgentContext context, PromptBuilder prompt) {
        for (String name : List.of("requirements_spec", "task_plan", "impact_report", "design_doc", "change_set",
                "test_report", "tests_evidence", "security_report", "review_report")) {
            prompt.artifact(context, name);
        }
        context.workspace().ifPresent(ws -> prompt.section("Commits on this run's branch",
                String.join("\n", ws.workspace().history(30))));
    }

    @Override
    protected AgentResult apply(AgentContext context, JsonNode output) {
        String notes = output.path("releaseNotes").asString("");
        String summary = output.path("engineeringSummary").asString("");
        if (notes.isBlank() || summary.isBlank()) {
            return AgentResult.failed("The answer needs non-empty releaseNotes and engineeringSummary (markdown)");
        }
        context.writeArtifact("release_notes", notes);
        context.writeArtifact("engineering_summary", summary);
        return AgentResult.ok("release notes written");
    }
}
