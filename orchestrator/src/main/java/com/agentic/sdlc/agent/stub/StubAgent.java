package com.agentic.sdlc.agent.stub;

import java.util.Map;

import com.agentic.sdlc.agent.Agent;
import com.agentic.sdlc.agent.AgentContext;
import com.agentic.sdlc.agent.AgentResult;

/**
 * Deterministic stand-in for an LLM agent: writes canned content for each declared output. Lets the whole
 * orchestration (gates, approvals, retries, re-planning) be exercised and demoed without any API key.
 */
public class StubAgent implements Agent {

    private final String name;
    private final Map<String, String> outputs;
    private final int failFirstAttempts;

    public StubAgent(String name, Map<String, String> outputs, int failFirstAttempts) {
        this.name = name;
        this.outputs = Map.copyOf(outputs);
        this.failFirstAttempts = failFirstAttempts;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        context.checkNotStopped();
        if (context.attemptNo() <= failFirstAttempts) {
            return AgentResult.failed("Simulated failure on attempt " + context.attemptNo());
        }
        for (String output : context.node().outputs()) {
            String content = outputs.getOrDefault(output, "{\"note\": \"stub output for " + output + "\"}");
            context.writeArtifact(output, content.replace("${requirement}", context.requirement()));
        }
        context.recordDecision(name + " produced " + context.node().outputs(), "Canned stub output", null);
        return AgentResult.ok("Stub output written");
    }
}
