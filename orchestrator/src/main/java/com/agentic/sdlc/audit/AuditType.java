package com.agentic.sdlc.audit;

/** Audit event types. Metrics are derived from some of them, so treat renames as a breaking change. */
public final class AuditType {

    public static final String RUN_STARTED = "RUN_STARTED";
    public static final String RUN_STATUS_CHANGED = "RUN_STATUS_CHANGED";
    public static final String RUN_STOPPED = "RUN_STOPPED";
    public static final String RUN_RESUMED = "RUN_RESUMED";
    public static final String RUN_RECOVERED = "RUN_RECOVERED";
    public static final String STAGE_STARTED = "STAGE_STARTED";
    public static final String STAGE_SKIPPED = "STAGE_SKIPPED";
    public static final String STAGE_SUCCEEDED = "STAGE_SUCCEEDED";
    public static final String STAGE_FAILED = "STAGE_FAILED";
    public static final String STAGE_INTERRUPTED = "STAGE_INTERRUPTED";
    public static final String STAGE_INVALIDATED = "STAGE_INVALIDATED";
    public static final String STAGE_MANUAL_RETRY = "STAGE_MANUAL_RETRY";
    public static final String ATTEMPT_FAILED = "ATTEMPT_FAILED";
    public static final String FALLBACK_ACTIVATED = "FALLBACK_ACTIVATED";
    public static final String POLICY_FINDING = "POLICY_FINDING";
    public static final String ARTIFACTS_COMMITTED = "ARTIFACTS_COMMITTED";
    public static final String ARTIFACT_REVISED = "ARTIFACT_REVISED";
    public static final String ROLLBACK = "ROLLBACK";
    public static final String APPROVAL_REQUESTED = "APPROVAL_REQUESTED";
    public static final String APPROVAL_GRANTED = "APPROVAL_GRANTED";
    public static final String APPROVAL_REJECTED = "APPROVAL_REJECTED";
    public static final String BUDGET_EXCEEDED = "BUDGET_EXCEEDED";
    public static final String GATE_EVIDENCE = "GATE_EVIDENCE";
    public static final String CHECKPOINT = "CHECKPOINT";
    public static final String WORKSPACE_PREPARED = "WORKSPACE_PREPARED";
    public static final String RELEASE_PUBLISHED = "RELEASE_PUBLISHED";
    public static final String CIRCUIT_BREAKER = "CIRCUIT_BREAKER";

    private AuditType() {
    }
}
