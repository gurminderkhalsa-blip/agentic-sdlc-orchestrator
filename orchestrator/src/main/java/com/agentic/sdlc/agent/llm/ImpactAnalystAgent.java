package com.agentic.sdlc.agent.llm;

import java.util.List;

import com.agentic.sdlc.agent.AgentContext;
import com.agentic.sdlc.agent.AgentResult;

import tools.jackson.databind.JsonNode;

/** Brownfield only: finds the modules, APIs and data affected by the change, from the actual code. */
public class ImpactAnalystAgent extends LlmAgent {

    public ImpactAnalystAgent(LlmSupport support) {
        super(support);
    }

    @Override
    public String name() {
        return "ImpactAnalystAgent";
    }

    @Override
    protected String promptName() {
        return "impact_analyst";
    }

    @Override
    protected void describeTask(AgentContext context, PromptBuilder prompt) {
        prompt.artifact(context, "requirements_spec");
        var ws = workspace(context);
        prompt.fileListing(ws).files(ws, List.of("src/", "build.gradle"));
    }

    @Override
    protected AgentResult apply(AgentContext context, JsonNode output) {
        context.writeArtifact("impact_report", withoutDecisions(output));
        return AgentResult.ok(output.path("impactedFiles").size() + " impacted files");
    }
}
