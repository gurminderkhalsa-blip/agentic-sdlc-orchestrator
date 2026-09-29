package com.agentic.sdlc.state;

import java.time.Instant;

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

/** A versioned, content-hashed output of a stage (spec, plan, design, code change set, report...). */
@Entity
@Table(name = "artifact", indexes = @Index(columnList = "runId,name"))
@Getter
@Setter
@NoArgsConstructor
public class Artifact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String runId;
    private String name;
    private int version;
    private String nodeId;
    private Long stageRunId;
    private Long attemptId;

    /** "agent:X" or "human:Y". */
    private String author;

    @Enumerated(EnumType.STRING)
    private ArtifactStatus status;

    @Lob
    private String content;

    private String contentHash;
    private Instant createdAt;
}
