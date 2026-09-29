package com.agentic.sdlc.agent.llm;

import java.util.List;

import com.agentic.sdlc.agent.AgentContext;
import com.agentic.sdlc.agent.AgentResult;

import tools.jackson.databind.JsonNode;

/** Turns the raw requirement into a normalised spec with explicit assumptions and open questions. */
public class RequirementsAgent extends LlmAgent {

    public RequirementsAgent(LlmSupport support) {
        super(support);
    }

    @Override
    public String name() {
        return "RequirementsAgent";
    }

    @Override
    protected String promptName() {
        return "requirements";
    }

    @Override
    protected void describeTask(AgentContext context, PromptBuilder prompt) {
        context.workspace().ifPresent(ws -> prompt.fileListing(ws));
    }

    @Override
    protected AgentResult apply(AgentContext context, JsonNode output) {
        if (!output.path("acceptanceCriteria").isArray() || output.path("acceptanceCriteria").isEmpty()) {
            return AgentResult.failed("The spec needs at least one entry in acceptanceCriteria");
        }
        context.writeArtifact("requirements_spec", withoutDecisions(output));
        return AgentResult.ok(output.path("summary").asString("spec written"));
    }
}
