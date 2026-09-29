package com.agentic.sdlc.agent.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.agentic.sdlc.config.SdlcProperties;
import com.agentic.sdlc.llm.LlmClient;

import tools.jackson.databind.ObjectMapper;

@Configuration
@ConditionalOnProperty(name = "sdlc.agents.mode", havingValue = "llm")
public class LlmAgentConfig {

    @Bean
    LlmSupport llmSupport(LlmClient llm, ObjectMapper json, SdlcProperties properties) {
        return new LlmSupport(llm, new PromptLibrary(), json, properties.llm());
    }

    @Bean
    RequirementsAgent requirementsAgent(LlmSupport s) {
        return new RequirementsAgent(s);
    }

    @Bean
    PlannerAgent plannerAgent(LlmSupport s) {
        return new PlannerAgent(s);
    }

    @Bean
    ImpactAnalystAgent impactAnalystAgent(LlmSupport s) {
        return new ImpactAnalystAgent(s);
    }

    @Bean
    ArchitectAgent architectAgent(LlmSupport s) {
        return new ArchitectAgent(s);
    }

    @Bean
    ImplementerAgent implementerAgent(LlmSupport s) {
        return new ImplementerAgent(s, false);
    }

    @Bean
    ImplementerAgent implementerFallbackAgent(LlmSupport s) {
        return new ImplementerAgent(s, true);
    }

    @Bean
    TestAgent testAgent(LlmSupport s) {
        return new TestAgent(s);
    }

    @Bean
    DocsAgent docsAgent(LlmSupport s) {
        return new DocsAgent(s);
    }

    @Bean
    SecurityReviewAgent securityReviewAgent(LlmSupport s) {
        return new SecurityReviewAgent(s);
    }

    @Bean
    ReviewerAgent reviewerAgent(LlmSupport s) {
        return new ReviewerAgent(s);
    }

    @Bean
    ReleaseAgent releaseAgent(LlmSupport s) {
        return new ReleaseAgent(s);
    }
}
