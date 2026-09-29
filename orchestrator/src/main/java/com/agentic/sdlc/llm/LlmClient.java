package com.agentic.sdlc.llm;

/** The only way agents reach a model. Implementations: live OpenAI, recording, replay. */
public interface LlmClient {

    LlmResponse complete(LlmRequest request);
}
