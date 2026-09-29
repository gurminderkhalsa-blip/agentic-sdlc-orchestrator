package com.agentic.sdlc.state;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One execution of a workflow for one requirement. Only {@code WorkflowEngine} changes it, under the run lock. */
@Entity
@Table(name = "workflow_run")
@Getter
@Setter
@NoArgsConstructor
public class WorkflowRun {

    @Id
    private String id;

    private String workflowName;

    @Lob
    private String requirement;

    @Enumerated(EnumType.STRING)
    private Scenario scenario;

    @Enumerated(EnumType.STRING)
    private RunStatus status;

    @Column(length = 2000)
    private String stopReason;

    private String startedBy;
    private Instant createdAt;
    private Instant endedAt;

    /** Start of the current budget window; reset when a human resumes a run that was stopped. */
    private Instant budgetWindowStart;

    /** Name of the LLM recording this run writes (record mode) or plays back (replay mode). */
    private String recording;

    /** Target-repository commit the run's workspace was cloned from. */
    private String baselineCommit;

    /** Target-repository commit after the release was published, if it was. */
    private String publishedCommit;
}
