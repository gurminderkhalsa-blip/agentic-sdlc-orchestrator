package com.agentic.sdlc.gate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TestReportsTest {

    @Test
    void failureDescriptionIncludesTheInnermostRootCause() {
        String trace = """
                java.lang.IllegalStateException: Failed to load ApplicationContext for [WebMergedContextConfiguration]
                \tat org.springframework.test.context.cache.DefaultCacheAwareContextLoaderDelegate.loadContext(X.java:1)
                \tat a.b(C.java:2)
                \tat a.b(C.java:3)
                \tat a.b(C.java:4)
                \tat a.b(C.java:5)
                \tat a.b(C.java:6)
                Caused by: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean 'linkService'
                \tat a.b(C.java:7)
                Caused by: org.springframework.beans.factory.NoSuchBeanDefinitionException: No qualifying bean of type 'java.time.Clock' available
                \tat a.b(C.java:8)
                """;

        String description = TestReports.describe(trace);

        assertThat(description).startsWith("java.lang.IllegalStateException: Failed to load ApplicationContext")
                .contains("Root cause: Caused by: org.springframework.beans.factory.NoSuchBeanDefinitionException: "
                        + "No qualifying bean of type 'java.time.Clock' available")
                .doesNotContain("C.java:7");
    }
}
