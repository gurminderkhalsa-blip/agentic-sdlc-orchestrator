package com.agentic.sdlc.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class BuildHintsTest {

    @Test
    void compileFeedbackNamesTheSpringBoot4Package() {
        String output = "src/test/java/X.java:9: error: package org.springframework.boot.test.autoconfigure.web.servlet does not exist\n"
                + "import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;\n       ^\n";
        BuildResult result = new BuildResult(List.of("test"), 1, false, 10, output);

        assertThat(result.failureSummary()).contains("Compilation errors:")
                .contains("Known fix: Spring Boot 4 moved the MockMvc test annotations")
                .contains("org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc");
    }

    @Test
    void noHintForUnrelatedErrors() {
        assertThat(BuildHints.forOutput("X.java:1: error: ';' expected")).isEmpty();
    }
}
