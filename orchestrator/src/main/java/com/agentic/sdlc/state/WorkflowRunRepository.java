package com.agentic.sdlc.state;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, String> {
    List<WorkflowRun> findAllByOrderByCreatedAtDesc();
}
