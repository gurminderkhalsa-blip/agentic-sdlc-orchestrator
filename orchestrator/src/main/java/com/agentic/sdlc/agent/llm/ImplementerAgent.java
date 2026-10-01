package com.agentic.sdlc.agent.llm;

import java.util.List;

import com.agentic.sdlc.agent.AgentContext;
import com.agentic.sdlc.agent.AgentResult;

import tools.jackson.databind.JsonNode;

/**
 * Writes production code into the workspace. The fallback instance uses the fallback model and a prompt
 * that asks for the most conservative change that meets the acceptance criteria.
 */
public class ImplementerAgent extends LlmAgent {

    private final boolean fallbackVariant;

    public ImplementerAgent(LlmSupport support, boolean fallbackVariant) {
        super(support);
        this.fallbackVariant = fallbackVariant;
    }

    @Override
    public String name() {
        return fallbackVariant ? "ImplementerFallbackAgent" : "ImplementerAgent";
    }

    @Override
    protected String promptName() {
        return fallbackVariant ? "implementer_fallback" : "implementer";
    }

    @Override
    protected List<String> systemFragments() {
        return List.of("_stack", "_code_output");
    }

    @Override
    protected void describeTask(AgentContext context, PromptBuilder prompt) {
        prompt.artifact(context, "requirements_spec").artifact(context, "task_plan").artifact(context, "impact_report")
                .artifact(context, "design_doc").artifact(context, "api_contract");
        var ws = workspace(context);
        prompt.fileListing(ws).files(ws, List.of("src/main/", "build.gradle", "src/test/"));
    }

    @Override
    protected AgentResult apply(AgentContext context, JsonNode output) {
        String problem = applyFiles(context, output);
        if (problem != null) {
            return AgentResult.failed(problem);
        }
        context.writeArtifact("change_set", changeSet(context, output));
        return AgentResult.ok(output.path("summary").asString("code written"));
    }
}
