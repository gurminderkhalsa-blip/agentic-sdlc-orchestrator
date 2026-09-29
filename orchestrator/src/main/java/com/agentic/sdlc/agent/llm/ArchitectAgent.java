package com.agentic.sdlc.agent.llm;

import java.util.List;

import com.agentic.sdlc.agent.AgentContext;
import com.agentic.sdlc.agent.AgentResult;

import tools.jackson.databind.JsonNode;

/** Produces the design document and the API contract the implementation and tests must follow. */
public class ArchitectAgent extends LlmAgent {

    public ArchitectAgent(LlmSupport support) {
        super(support);
    }

    @Override
    public String name() {
        return "ArchitectAgent";
    }

    @Override
    protected String promptName() {
        return "architect";
    }

    @Override
    protected List<String> systemFragments() {
        return List.of("_stack");
    }

    @Override
    protected void describeTask(AgentContext context, PromptBuilder prompt) {
        prompt.artifact(context, "requirements_spec").artifact(context, "task_plan").artifact(context, "impact_report");
        var ws = workspace(context);
        prompt.fileListing(ws).files(ws, List.of("src/main/"));
    }

    @Override
    protected AgentResult apply(AgentContext context, JsonNode output) {
        String designDoc = output.path("designDoc").asString("");
        JsonNode contract = output.path("apiContract");
        if (designDoc.isBlank() || !contract.isArray() || contract.isEmpty()) {
            return AgentResult.failed("The answer needs a non-empty designDoc (markdown) and apiContract array");
        }
        context.writeArtifact("design_doc", designDoc);
        context.writeArtifact("api_contract", pretty(contract));
        return AgentResult.ok("design with " + contract.size() + " API operations");
    }
}
