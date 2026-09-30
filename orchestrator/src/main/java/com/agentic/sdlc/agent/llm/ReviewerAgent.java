package com.agentic.sdlc.agent.llm;

import java.util.List;

import com.agentic.sdlc.agent.AgentContext;
import com.agentic.sdlc.agent.AgentResult;
import com.agentic.sdlc.workspace.Workspace;

import tools.jackson.databind.JsonNode;

/** Final technical review at the join: checks every acceptance criterion against the evidence. */
public class ReviewerAgent extends LlmAgent {

    public ReviewerAgent(LlmSupport support) {
        super(support);
    }

    @Override
    public String name() {
        return "ReviewerAgent";
    }

    @Override
    protected String promptName() {
        return "reviewer";
    }

    @Override
    protected void describeTask(AgentContext context, PromptBuilder prompt) {
        for (String name : List.of("requirements_spec", "task_plan", "impact_report", "design_doc", "api_contract",
                "change_set", "test_report", "tests_evidence", "security_report", "documentation")) {
            prompt.artifact(context, name);
        }
        context.workspace().ifPresent(ws -> {
            List<String> changed = ws.workspace().changedFilesSince(Workspace.BASELINE_REF).stream()
                    .filter(p -> p.startsWith("src/") || p.equals("build.gradle")).toList();
            prompt.section("Files changed by this run (review every one; list each in filesReviewed)",
                    String.join("\n", changed));
            prompt.files(ws, changed);
        });
    }

    @Override
    protected AgentResult apply(AgentContext context, JsonNode output) {
        context.writeArtifact("review_report", withoutDecisions(output));
        return AgentResult.ok("verdict: " + output.path("verdict").asString("?"));
    }
}
