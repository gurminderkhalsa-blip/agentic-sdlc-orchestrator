package com.agentic.sdlc.gate;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Exit gate: every output is well-formed JSON (used for machine-read artifacts like specs and plans). */
@Component
public class ValidJsonGate implements Gate {

    private final ObjectMapper json;

    public ValidJsonGate(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public String name() {
        return "validJson";
    }

    @Override
    public GateResult check(GateContext context) {
        List<String> problems = new ArrayList<>();
        for (String output : context.node().outputs()) {
            context.artifact(output).ifPresent(content -> {
                try {
                    json.readTree(content);
                } catch (JacksonException e) {
                    problems.add(output + " is not valid JSON: " + e.getOriginalMessage());
                }
            });
        }
        return problems.isEmpty() ? GateResult.pass() : GateResult.fail(String.join("; ", problems));
    }
}
