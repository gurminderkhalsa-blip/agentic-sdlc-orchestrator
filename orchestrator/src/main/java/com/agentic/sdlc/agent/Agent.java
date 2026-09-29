package com.agentic.sdlc.agent;

import com.agentic.sdlc.common.Named;

/**
 * A worker that performs one kind of SDLC task. Agents never touch state directly: they read inputs and
 * propose outputs through the {@link AgentContext}; the engine decides whether those outputs are committed.
 */
public interface Agent extends Named {

    AgentResult execute(AgentContext context) throws Exception;
}
