package com.agentic.sdlc.agent;

import java.util.List;

import org.springframework.stereotype.Component;

import com.agentic.sdlc.common.Registry;

@Component
public class AgentRegistry extends Registry<Agent> {
    public AgentRegistry(List<Agent> agents) {
        super("agent", agents);
    }
}
