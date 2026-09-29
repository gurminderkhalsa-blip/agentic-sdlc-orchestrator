package com.agentic.sdlc.state;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ArtifactRepository extends JpaRepository<Artifact, Long> {
    Optional<Artifact> findFirstByRunIdAndNameAndStatusOrderByVersionDesc(String runId, String name, ArtifactStatus status);

    List<Artifact> findByRunIdOrderByIdAsc(String runId);

    List<Artifact> findByRunIdAndNodeIdAndStatus(String runId, String nodeId, ArtifactStatus status);

    List<Artifact> findByRunIdAndNameOrderByVersionAsc(String runId, String name);

    @Query("select coalesce(max(a.version), 0) from Artifact a where a.runId = :runId and a.name = :name")
    int maxVersion(String runId, String name);
}
