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

/** Decision lineage: what was decided, by whom, why, and which input versions it was based on. */
@Entity
@Table(name = "decision", indexes = @Index(columnList = "runId"))
@Getter
@Setter
@NoArgsConstructor
public class Decision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String runId;
    private String nodeId;
    private Long stageRunId;
    private String actor;

    @Column(length = 500)
    private String title;

    @Lob
    private String rationale;

    @Lob
    private String alternatives;

    @Lob
    private String inputHashes;

    private Instant createdAt;
}
