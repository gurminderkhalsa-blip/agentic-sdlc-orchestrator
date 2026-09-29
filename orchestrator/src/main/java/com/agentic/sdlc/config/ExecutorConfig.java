package com.agentic.sdlc.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExecutorConfig {

    /** Stages spend most of their time waiting on LLM calls and builds, so one virtual thread per stage is cheap. */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService stageExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("stage-", 0).factory());
    }
}
