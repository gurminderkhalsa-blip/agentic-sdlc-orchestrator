package com.agentic.sdlc.state;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StageRunRepository extends JpaRepository<StageRun, Long> {
    List<StageRun> findByRunIdOrderByIdAsc(String runId);

    Optional<StageRun> findByRunIdAndNodeId(String runId, String nodeId);
}
