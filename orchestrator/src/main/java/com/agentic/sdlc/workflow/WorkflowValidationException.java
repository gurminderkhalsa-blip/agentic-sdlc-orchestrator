package com.agentic.sdlc.workflow;

import java.util.List;

public class WorkflowValidationException extends RuntimeException {

    private final List<String> problems;

    public WorkflowValidationException(String source, List<String> problems) {
        super("Invalid workflow " + source + ":\n - " + String.join("\n - ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
