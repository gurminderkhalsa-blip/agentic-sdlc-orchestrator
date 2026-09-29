package com.agentic.sdlc.workflow;

import java.util.List;

/**
 * One stage of a workflow, exactly as declared in the workflow YAML.
 *
 * @param id               unique stage id, e.g. "design"
 * @param agent            name of the agent that does the work
 * @param dependsOn        stages that must be SUCCEEDED (or SKIPPED) before this one may start
 * @param condition        optional condition name; when false the stage is SKIPPED
 * @param outputs          artifact names this stage must produce
 * @param entryGates       checks that must pass before the agent runs
 * @param exitGates        checks the agent's output must pass before it is committed
 * @param requiresApproval pause for a human decision after the exit gates pass
 * @param maxRetries       extra attempts for the primary agent after the first one fails
 * @param fallbackAgent    optional agent tried after the primary agent is exhausted
 * @param fallbackAttempts attempts given to the fallback agent
 * @param writes           workspace paths (globs) this stage may change; anything else is a policy violation
 * @param publishOnApproval merge the run's branch into the target repository when this stage is approved
 */
public record NodeDefinition(
        String id,
        String agent,
        List<String> dependsOn,
        String condition,
        List<String> outputs,
        List<String> entryGates,
        List<String> exitGates,
        Boolean requiresApproval,
        Integer maxRetries,
        String fallbackAgent,
        Integer fallbackAttempts,
        List<String> writes,
        Boolean publishOnApproval) {

    public NodeDefinition {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        entryGates = entryGates == null ? List.of() : List.copyOf(entryGates);
        exitGates = exitGates == null ? List.of() : List.copyOf(exitGates);
        requiresApproval = requiresApproval != null && requiresApproval;
        maxRetries = maxRetries == null ? 0 : maxRetries;
        fallbackAttempts = fallbackAttempts == null ? 1 : fallbackAttempts;
        writes = writes == null ? List.of() : List.copyOf(writes);
        publishOnApproval = publishOnApproval != null && publishOnApproval;
    }

    public boolean needsApproval() {
        return requiresApproval;
    }

    public boolean publishes() {
        return publishOnApproval;
    }
}
