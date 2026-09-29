package com.agentic.sdlc.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * All orchestrator settings, bound from the {@code sdlc.*} keys in application.yml.
 *
 * @param workflowLocations resource patterns the workflow YAML files are loaded from
 * @param agents            which agent implementations are active
 * @param retryBackoff      pause between retry attempts of the same stage
 * @param budget            hard limits per run; exceeding any of them safe-stops the run
 * @param policy            guardrail settings
 * @param defaultWorkflow   workflow used when a run request does not name one
 * @param llm               LLM provider settings (used when agents.mode=llm)
 * @param workspace         where per-run git workspaces and the target repository live
 * @param gates             thresholds used by quality gates
 */
@ConfigurationProperties(prefix = "sdlc")
public record SdlcProperties(
        @DefaultValue("classpath:workflows/*.yaml") List<String> workflowLocations,
        @DefaultValue Agents agents,
        @DefaultValue("500ms") Duration retryBackoff,
        @DefaultValue Budget budget,
        @DefaultValue Policy policy,
        @DefaultValue("sdlc") String defaultWorkflow,
        @DefaultValue Llm llm,
        @DefaultValue Workspace workspace,
        @DefaultValue Gates gates) {

    /** @param mode "stub" (canned outputs, no LLM) or "llm" (real agents, added on Day 2) */
    public record Agents(@DefaultValue("stub") String mode) {
    }

    public record Budget(
            @DefaultValue("30m") Duration maxRunDuration,
            @DefaultValue("40") int maxAttempts,
            @DefaultValue("500000") long maxTokens) {
    }

    public record Policy(
            @DefaultValue("200000") int maxArtifactChars,
            @DefaultValue("40") int maxFilesPerChange,
            @DefaultValue("4000") int maxLinesPerChange) {
    }

    /**
     * @param mode            live (call OpenAI), record (call OpenAI and save responses), replay (saved responses only)
     * @param model           model for all agents
     * @param fallbackModel   model for fallback agents; defaults to {@code model}
     * @param apiKey          read from OPENAI_API_KEY; never logged or passed to builds
     * @param recordingsDir   where record mode writes and replay mode reads
     * @param reasoningEffort optional (low, medium, high) for reasoning models
     */
    public record Llm(
            @DefaultValue("live") String mode,
            @DefaultValue("gpt-5.6-luna") String model,
            String fallbackModel,
            String apiKey,
            @DefaultValue("./scenarios/recordings") String recordingsDir,
            @DefaultValue("32000") long maxOutputTokens,
            @DefaultValue("5m") Duration timeout,
            String reasoningEffort) {

        public String effectiveFallbackModel() {
            return fallbackModel == null || fallbackModel.isBlank() ? model : fallbackModel;
        }
    }

    /**
     * @param root         per-run workspaces are cloned to {@code root/runs/<runId>}
     * @param targetRepo   the repository being changed; created from {@code template} on first use
     * @param template     starting point for a greenfield target repository
     * @param buildTimeout hard limit for one Gradle invocation inside a workspace
     */
    public record Workspace(
            @DefaultValue("./workspace") String root,
            @DefaultValue("./workspace/target/url-shortener") String targetRepo,
            @DefaultValue("./templates/spring-boot-service") String template,
            @DefaultValue("10m") Duration buildTimeout) {
    }

    public record Gates(@DefaultValue("0.7") double minLineCoverage) {
    }
}
