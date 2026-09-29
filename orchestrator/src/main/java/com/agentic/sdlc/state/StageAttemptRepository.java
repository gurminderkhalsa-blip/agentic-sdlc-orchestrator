package com.agentic.sdlc.state;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StageAttemptRepository extends JpaRepository<StageAttempt, Long> {
    List<StageAttempt> findByRunIdOrderByIdAsc(String runId);

    @Query("select count(a) from StageAttempt a where a.runId = :runId and a.startedAt >= :since")
    long countSince(String runId, java.time.Instant since);

    @Query("select coalesce(sum(a.tokensUsed), 0) from StageAttempt a where a.runId = :runId and a.startedAt >= :since")
    long tokensSince(String runId, java.time.Instant since);
}
