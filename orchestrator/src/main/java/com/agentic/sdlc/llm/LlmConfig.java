package com.agentic.sdlc.llm;

import java.nio.file.Path;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.agentic.sdlc.config.SdlcProperties;

@Configuration
@ConditionalOnProperty(name = "sdlc.agents.mode", havingValue = "llm")
public class LlmConfig {

    @Bean
    RecordingStore recordingStore(SdlcProperties properties) {
        return new RecordingStore(Path.of(properties.llm().recordingsDir()).toAbsolutePath().normalize());
    }

    @Bean
    LlmClient llmClient(SdlcProperties properties, RecordingStore store) {
        SdlcProperties.Llm llm = properties.llm();
        return switch (llm.mode()) {
            case "live" -> new OpenAiLlmClient(llm);
            case "record" -> new RecordingLlmClient(new OpenAiLlmClient(llm), store);
            case "replay" -> new ReplayLlmClient(store);
            default -> throw new IllegalStateException("sdlc.llm.mode must be live, record or replay, not " + llm.mode());
        };
    }
}
