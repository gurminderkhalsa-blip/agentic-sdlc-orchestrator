package com.agentic.sdlc.agent.llm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.core.io.ClassPathResource;

/** System prompts live in resources/prompts/*.md so they can be reviewed and versioned like code. */
public class PromptLibrary {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String get(String name) {
        return cache.computeIfAbsent(name, n -> {
            try (InputStream in = new ClassPathResource("prompts/" + n + ".md").getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("Missing prompt prompts/" + n + ".md", e);
            }
        });
    }
}
