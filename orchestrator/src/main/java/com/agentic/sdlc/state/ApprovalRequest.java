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

/** A human checkpoint. The stage stays AWAITING_APPROVAL until someone approves or rejects it. */
@Entity
@Table(name = "approval_request", indexes = @Index(columnList = "runId"))
@Getter
@Setter
@NoArgsConstructor
public class ApprovalRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String runId;
    private Long stageRunId;
    private String nodeId;

    @Enumerated(EnumType.STRING)
    private ApprovalReason reason;

    /** What the reviewer is asked to look at, e.g. policy findings or open questions. */
    @Lob
    private String summary;

    @Enumerated(EnumType.STRING)
    private ApprovalStatus status;

    private Instant requestedAt;
    private Instant decidedAt;
    private String decidedBy;

    @Column(length = 4000)
    private String comment;
}
