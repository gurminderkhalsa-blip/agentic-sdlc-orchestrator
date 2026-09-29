package com.agentic.sdlc.condition;

import org.springframework.stereotype.Component;

import com.agentic.sdlc.gate.NoOpenQuestionsGate;
import com.agentic.sdlc.state.Scenario;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * True when the work changes existing code. The requirements spec's {@code changeType} wins over the
 * scenario hint given at start, so a human correcting the spec re-routes the workflow.
 */
@Component
public class IsBrownfieldCondition implements NodeCondition {

    private final ObjectMapper json;

    public IsBrownfieldCondition(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public String name() {
        return "isBrownfield";
    }

    @Override
    public boolean test(ConditionContext context) {
        String changeType = context.artifact(NoOpenQuestionsGate.SPEC_ARTIFACT)
                .map(this::changeTypeOf)
                .orElse("");
        if (!changeType.isBlank()) {
            return changeType.equalsIgnoreCase("brownfield");
        }
        return context.scenario() == Scenario.BROWNFIELD;
    }

    private String changeTypeOf(String spec) {
        try {
            return json.readTree(spec).path("changeType").asString("");
        } catch (JacksonException e) {
            return "";
        }
    }
}
