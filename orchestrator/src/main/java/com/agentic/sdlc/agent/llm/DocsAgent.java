package com.agentic.sdlc.agent.llm;

import java.util.List;

import com.agentic.sdlc.agent.AgentContext;
import com.agentic.sdlc.agent.AgentResult;

import tools.jackson.databind.JsonNode;

/** Writes the README and API documentation for the service. */
public class DocsAgent extends LlmAgent {

    public DocsAgent(LlmSupport support) {
        super(support);
    }

    @Override
    public String name() {
        return "DocsAgent";
    }

    @Override
    protected String promptName() {
        return "docs";
    }

    @Override
    protected List<String> systemFragments() {
        return List.of("_code_output");
    }

    @Override
    protected void describeTask(AgentContext context, PromptBuilder prompt) {
        prompt.artifact(context, "requirements_spec").artifact(context, "api_contract").artifact(context, "design_doc")
                .artifact(context, "change_set");
        var ws = workspace(context);
        prompt.fileListing(ws).files(ws, List.of("README.md", "docs/"));
    }

    @Override
    protected AgentResult apply(AgentContext context, JsonNode output) {
        String problem = applyFiles(context, output);
        if (problem != null) {
            return AgentResult.failed(problem);
        }
        context.writeArtifact("documentation", changeSet(context, output));
        return AgentResult.ok(output.path("summary").asString("docs written"));
    }
}
