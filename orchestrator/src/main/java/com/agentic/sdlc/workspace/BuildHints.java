package com.agentic.sdlc.workspace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Known fixes for compile errors models make repeatedly on this stack (mostly Spring Boot 3 / Jackson 2
 * habits). A raw "package does not exist" error does not tell the agent what the right package is; these
 * hints do, and they are appended to build feedback whenever the pattern appears.
 */
public final class BuildHints {

    private static final Map<Pattern, String> HINTS = new LinkedHashMap<>();

    static {
        HINTS.put(Pattern.compile("org\\.springframework\\.boot\\.test\\.autoconfigure\\.web\\.servlet"),
                "Spring Boot 4 moved the MockMvc test annotations: use "
                        + "org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc and "
                        + "org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest.");
        HINTS.put(Pattern.compile("org\\.springframework\\.boot\\.test\\.mock\\.mockito|cannot find symbol.*MockBean"),
                "@MockBean no longer exists: use org.springframework.test.context.bean.override.mockito.MockitoBean.");
        HINTS.put(Pattern.compile("org\\.springframework\\.boot\\.test\\.autoconfigure\\.orm\\.jpa"),
                "@DataJpaTest is not on the classpath in this project: use @SpringBootTest with the real H2 database.");
        HINTS.put(Pattern.compile("com\\.fasterxml\\.jackson\\.databind"),
                "Spring Boot 4 uses Jackson 3: databind classes are tools.jackson.databind.* "
                        + "(annotations stay com.fasterxml.jackson.annotation.*).");
        HINTS.put(Pattern.compile("package javax\\.(persistence|validation)"),
                "Use jakarta.persistence / jakarta.validation, not javax.*.");
        HINTS.put(Pattern.compile("TestRestTemplate|WebTestClient"),
                "TestRestTemplate and WebTestClient are not on the classpath: use MockMvc.");
    }

    private BuildHints() {
    }

    /** Hints whose pattern occurs in the build output, in a stable order. */
    public static List<String> forOutput(String output) {
        return HINTS.entrySet().stream().filter(e -> e.getKey().matcher(output).find()).map(Map.Entry::getValue).toList();
    }
}
