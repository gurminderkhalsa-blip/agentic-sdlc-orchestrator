package com.agentic.sdlc.api;

import com.agentic.sdlc.state.Scenario;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Request bodies. {@code actor} identifies the human for the audit trail (real auth is out of scope). */
public final class ApiRequests {

    private ApiRequests() {
    }

    /** @param recording LLM recording name, for record and replay modes */
    public record StartRun(@NotBlank String requirement, Scenario scenario, String workflow, @NotBlank String actor,
            @Pattern(regexp = "[A-Za-z0-9._-]+") String recording) {
    }

    public record Rerun(@NotBlank String actor, String feedback) {
    }

    /** @param name folder under deliverables/; defaults to the run's recording name */
    public record Export(@Pattern(regexp = "[A-Za-z0-9._-]+") String name) {
    }

    public record Decide(@NotBlank String actor, String comment) {
    }

    public record Stop(@NotBlank String actor, @NotBlank String reason) {
    }

    public record Act(@NotBlank String actor) {
    }

    public record Revise(@NotBlank String actor, @NotBlank String content, @NotBlank String reason) {
    }
}
