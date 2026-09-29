package com.agentic.sdlc.state;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
    List<AuditEvent> findByRunIdOrderByIdAsc(String runId);

    long countByRunIdAndType(String runId, String type);

    long countByType(String type);
}
