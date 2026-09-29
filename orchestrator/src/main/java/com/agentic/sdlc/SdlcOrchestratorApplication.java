package com.agentic.sdlc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SdlcOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SdlcOrchestratorApplication.class, args);
    }
}
