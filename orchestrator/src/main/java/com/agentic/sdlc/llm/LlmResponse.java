package com.agentic.sdlc.llm;

public record LlmResponse(String content, String model, long promptTokens, long completionTokens) {

    public long totalTokens() {
        return promptTokens + completionTokens;
    }
}
