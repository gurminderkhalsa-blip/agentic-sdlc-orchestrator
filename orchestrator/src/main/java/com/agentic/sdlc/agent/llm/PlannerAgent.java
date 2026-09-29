package com.agentic.sdlc.agent.llm;

import java.util.List;

import com.agentic.sdlc.agent.AgentContext;
import com.agentic.sdlc.agent.AgentResult;

import tools.jackson.databind.JsonNode;

/** Decomposes the spec into tasks with dependencies and sequencing. */
public class PlannerAgent extends LlmAgent {

    public PlannerAgent(LlmSupport support) {
        super(support);
    }

    @Override
    public String name() {
        return "PlannerAgent";
    }

    @Override
    protected String promptName() {
        return "planner";
    }

    @Override
    protected void describeTask(AgentContext context, PromptBuilder prompt) {
        prompt.artifact(context, "requirements_spec");
        context.workspace().ifPresent(ws -> prompt.fileListing(ws));
    }

    @Override
    protected AgentResult apply(AgentContext context, JsonNode output) {
        context.writeArtifact("task_plan", withoutDecisions(output));
        return AgentResult.ok(output.path("tasks").size() + " tasks planned");
    }
}
