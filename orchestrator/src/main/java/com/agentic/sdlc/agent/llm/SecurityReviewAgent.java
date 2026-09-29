package com.agentic.sdlc.agent.llm;

import java.util.List;

import com.agentic.sdlc.agent.AgentContext;
import com.agentic.sdlc.agent.AgentResult;

import tools.jackson.databind.JsonNode;

/** Reviews the production code for security issues. Read-only: it reports, it never edits code. */
public class SecurityReviewAgent extends LlmAgent {

    public SecurityReviewAgent(LlmSupport support) {
        super(support);
    }

    @Override
    public String name() {
        return "SecurityReviewAgent";
    }

    @Override
    protected String promptName() {
        return "security_reviewer";
    }

    @Override
    protected void describeTask(AgentContext context, PromptBuilder prompt) {
        prompt.artifact(context, "requirements_spec").artifact(context, "change_set");
        var ws = workspace(context);
        prompt.files(ws, List.of("src/main/", "build.gradle"));
    }

    @Override
    protected AgentResult apply(AgentContext context, JsonNode output) {
        if (!output.path("findings").isArray()) {
            return AgentResult.failed("The answer needs a findings array (it may be empty)");
        }
        context.writeArtifact("security_report", withoutDecisions(output));
        return AgentResult.ok(output.path("findings").size() + " findings");
    }
}
