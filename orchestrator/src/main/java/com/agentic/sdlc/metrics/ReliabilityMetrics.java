package com.agentic.sdlc.metrics;

import java.util.Map;

/**
 * Aggregate reliability numbers across all runs.
 *
 * @param successRate          succeeded / finished runs (null until a run finishes)
 * @param firstPassStageRate   stage executions that succeeded on their first attempt
 * @param retryRate            retries / attempts
 * @param rollbacksPerRun      average withdrawals per run
 * @param mttrMs               mean time to recovery over every recovered stage failure
 * @param avgEndToEndMs        average latency of succeeded runs
 */
public record ReliabilityMetrics(
        long totalRuns,
        Map<String, Long> runsByStatus,
        Double successRate,
        Double firstPassStageRate,
        Double retryRate,
        Double rollbacksPerRun,
        Long mttrMs,
        Long avgEndToEndMs,
        long totalAttempts,
        long totalRollbacks) {
}
