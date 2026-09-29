package com.agentic.sdlc.common;

/** Anything that a workflow YAML file refers to by name (agents, gates, conditions, policy rules). */
public interface Named {
    String name();
}
