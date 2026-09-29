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
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** State of one workflow node inside one run. */
@Entity
@Table(name = "stage_run", indexes = @Index(columnList = "runId"))
@Getter
@Setter
@NoArgsConstructor
public class StageRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String runId;
    private String nodeId;

    @Enumerated(EnumType.STRING)
    private StageStatus status;

    /** Attempt counter across retries, fallbacks, human rejections and re-runs. */
    private int attemptCount;

    private Instant startedAt;
    private Instant endedAt;

    @Column(length = 4000)
    private String lastError;

    /** Feedback from a human rejection or answered questions, handed to the next attempt. */
    @Lob
    private String humanFeedback;

    /** JSON map of input artifact name to content hash, captured when the output was committed (lineage). */
    @Lob
    private String inputHashes;

    public StageRun(String runId, String nodeId) {
        this.runId = runId;
        this.nodeId = nodeId;
        this.status = StageStatus.PENDING;
    }
}
