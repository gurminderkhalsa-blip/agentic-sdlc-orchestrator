package com.agentic.sdlc.metrics;

import java.util.List;

/**
 * Reliability numbers for one run.
 *
 * @param endToEndMs  wall-clock time from start to finish (or now, if unfinished)
 * @param agentTimeMs time spent inside agent attempts
 * @param humanWaitMs time stages spent waiting for approval decisions
 * @param retries     attempts beyond the first for each stage that ran
 * @param rollbacks   times proposed or committed outputs were withdrawn
 * @param mttrMs      mean time from a failed attempt to the next successful attempt of the same stage
 */
public record RunMetrics(
        String runId,
        String status,
        long endToEndMs,
        long agentTimeMs,
        long humanWaitMs,
        int attempts,
        int failedAttempts,
        int retries,
        long rollbacks,
        int approvalsRequested,
        int approvalsRejected,
        long tokensUsed,
        Long mttrMs,
        List<StageMetrics> stages) {

    public record StageMetrics(String nodeId, String status, int attempts, Long durationMs) {
    }
}
