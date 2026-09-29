package com.agentic.sdlc.agent.llm;

import java.util.List;

import com.agentic.sdlc.agent.AgentContext;
import com.agentic.sdlc.agent.AgentResult;

import tools.jackson.databind.JsonNode;

/** Writes unit and integration tests against the acceptance criteria and API contract. */
public class TestAgent extends LlmAgent {

    public TestAgent(LlmSupport support) {
        super(support);
    }

    @Override
    public String name() {
        return "TestAgent";
    }

    @Override
    protected String promptName() {
        return "tester";
    }

    @Override
    protected List<String> systemFragments() {
        return List.of("_stack", "_code_output");
    }

    @Override
    protected void describeTask(AgentContext context, PromptBuilder prompt) {
        prompt.artifact(context, "requirements_spec").artifact(context, "api_contract").artifact(context, "design_doc");
        var ws = workspace(context);
        prompt.fileListing(ws).files(ws, List.of("src/"));
    }

    @Override
    protected AgentResult apply(AgentContext context, JsonNode output) {
        String problem = applyFiles(context, output);
        if (problem != null) {
            return AgentResult.failed(problem);
        }
        var report = json.createObjectNode();
        report.put("summary", output.path("summary").asString(""));
        report.set("testCases", output.path("testCases"));
        report.set("implementationDefects", output.path("implementationDefects"));
        context.writeArtifact("test_report", pretty(report));
        return AgentResult.ok(output.path("testCases").size() + " test cases written");
    }
}
