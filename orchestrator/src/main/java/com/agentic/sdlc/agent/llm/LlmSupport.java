package com.agentic.sdlc.agent.llm;

import com.agentic.sdlc.config.SdlcProperties;
import com.agentic.sdlc.llm.LlmClient;

import tools.jackson.databind.ObjectMapper;

/** Shared collaborators every LLM agent needs. */
public record LlmSupport(LlmClient llm, PromptLibrary prompts, ObjectMapper json, SdlcProperties.Llm config) {
}
