package com.agentic.sdlc.state;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, Long> {
    List<ApprovalRequest> findByRunIdOrderByIdAsc(String runId);

    List<ApprovalRequest> findByStatusOrderByIdAsc(ApprovalStatus status);

    List<ApprovalRequest> findByStageRunIdAndStatus(Long stageRunId, ApprovalStatus status);
}
