package com.agentic.sdlc.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.agentic.sdlc.agent.Agent;
import com.agentic.sdlc.agent.AgentContext;
import com.agentic.sdlc.agent.AgentResult;

/** Test agent whose behaviour each test scripts; records every context it was called with. */
public class ScriptedAgent implements Agent {

    @FunctionalInterface
    public interface Behavior {
        AgentResult run(AgentContext context) throws Exception;
    }

    private final String name;
    private final List<AgentContext> calls = new CopyOnWriteArrayList<>();
    private volatile Behavior behavior = ScriptedAgent::writeDefaults;

    public ScriptedAgent(String name) {
        this.name = name;
    }

    /** Writes {"by": agent, "attempt": n} to every declared output. */
    public static AgentResult writeDefaults(AgentContext context) {
        for (String output : context.node().outputs()) {
            context.writeArtifact(output,
                    "{\"by\": \"" + context.node().agent() + "\", \"attempt\": " + context.attemptNo() + "}");
        }
        return AgentResult.ok("wrote defaults");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public AgentResult execute(AgentContext context) throws Exception {
        calls.add(context);
        return behavior.run(context);
    }

    public void script(Behavior behavior) {
        this.behavior = behavior;
    }

    public List<AgentContext> calls() {
        return calls;
    }

    public void reset() {
        calls.clear();
        behavior = ScriptedAgent::writeDefaults;
    }
}
