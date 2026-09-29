package com.agentic.sdlc.state;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Append-only audit trail. Rows are never updated or deleted. */
@Entity
@Table(name = "audit_event", indexes = @Index(columnList = "runId"))
@Getter
@Setter
@NoArgsConstructor
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String runId;
    private String nodeId;
    private String type;
    private String actor;

    @Column(length = 4000)
    private String message;

    /** Optional JSON with structured details. */
    @Lob
    private String details;

    private Instant timestamp;
}
