package com.agentic.sdlc.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.agentic.sdlc.config.SdlcProperties;
import com.agentic.sdlc.state.StageAttemptRepository;
import com.agentic.sdlc.state.WorkflowRun;

/**
 * Hard caps on how much a run may do before a human has to look at it. Usage is counted from the attempt
 * table since the current budget window started, so a human resume grants a fresh window.
 */
@Component
public class BudgetGuard {

    private final SdlcProperties.Budget budget;
    private final StageAttemptRepository attempts;

    public BudgetGuard(SdlcProperties properties, StageAttemptRepository attempts) {
        this.budget = properties.budget();
        this.attempts = attempts;
    }

    public Optional<String> exceeded(WorkflowRun run) {
        Instant since = run.getBudgetWindowStart();
        Duration elapsed = Duration.between(since, Instant.now());
        if (elapsed.compareTo(budget.maxRunDuration()) > 0) {
            return Optional.of("run duration " + elapsed.toSeconds() + "s exceeds " + budget.maxRunDuration().toSeconds() + "s");
        }
        long used = attempts.countSince(run.getId(), since);
        if (used >= budget.maxAttempts()) {
            return Optional.of(used + " attempts reached the limit of " + budget.maxAttempts());
        }
        long tokens = attempts.tokensSince(run.getId(), since);
        if (tokens >= budget.maxTokens()) {
            return Optional.of(tokens + " tokens reached the limit of " + budget.maxTokens());
        }
        return Optional.empty();
    }
}
