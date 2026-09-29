package com.agentic.sdlc.state;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One try of one agent at one stage. Retry rate, MTTR and token budgets are all computed from these rows. */
@Entity
@Table(name = "stage_attempt", indexes = { @Index(columnList = "runId"), @Index(columnList = "stageRunId") })
@Getter
@Setter
@NoArgsConstructor
public class StageAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String runId;
    private Long stageRunId;
    private String nodeId;
    private int attemptNo;
    private String agent;
    private boolean fallback;

    @Enumerated(EnumType.STRING)
    private AttemptStatus status;

    @Column(length = 4000)
    private String failureReason;

    private long tokensUsed;

    /** Git commit that checkpointed this attempt's workspace changes, if it changed any files. */
    private String checkpointCommit;
    private Instant startedAt;
    private Instant endedAt;
}
