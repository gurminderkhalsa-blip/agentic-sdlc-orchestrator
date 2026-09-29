package com.agentic.sdlc.gate;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Exit gate for the requirements spec. Each entry in {@code openQuestions} must either be answered by a
 * human or carry an explicit {@code assumption}. Blocking questions without an assumption go to a human,
 * because another agent attempt cannot invent the business answer.
 */
@Component
public class NoOpenQuestionsGate implements Gate {

    public static final String SPEC_ARTIFACT = "requirements_spec";

    private final ObjectMapper json;

    public NoOpenQuestionsGate(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public String name() {
        return "noOpenQuestions";
    }

    @Override
    public GateResult check(GateContext context) {
        String content = context.artifact(SPEC_ARTIFACT).orElse(null);
        if (content == null) {
            return GateResult.fail(SPEC_ARTIFACT + " was not produced");
        }
        JsonNode spec;
        try {
            spec = json.readTree(content);
        } catch (JacksonException e) {
            return GateResult.fail(SPEC_ARTIFACT + " is not valid JSON");
        }
        List<String> unresolved = new ArrayList<>();
        for (JsonNode question : spec.path("openQuestions")) {
            boolean answered = !question.path("answer").asString("").isBlank();
            boolean assumed = !question.path("assumption").asString("").isBlank();
            if (!answered && !assumed) {
                unresolved.add(question.path("question").asString(question.toString()));
            }
        }
        return unresolved.isEmpty() ? GateResult.pass()
                : GateResult.needsHuman("Requirement needs clarification: " + String.join(" | ", unresolved));
    }
}
