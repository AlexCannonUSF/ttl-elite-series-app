package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.PaperTradeDecisionSample;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface PaperTradeDecisionSampleRepository extends JpaRepository<PaperTradeDecisionSample, Long> {

    long countBySessionId(Long sessionId);

    long countBySessionIdAndDecisionStatus(Long sessionId, String decisionStatus);

    long countBySessionIdAndFallbackPickTrueAndDecisionStatus(Long sessionId, String decisionStatus);

    List<PaperTradeDecisionSample> findBySessionIdOrderByCreatedAtAsc(Long sessionId);

    List<PaperTradeDecisionSample> findBySessionIdOrderByCreatedAtDesc(Long sessionId, Pageable pageable);

    /**
     * Used by {@code CounterfactualSkippedPickAnalysisService} to scan all
     * "would have been a bet but the staking policy passed on it" rows in a
     * recent window for outcome attribution.
     */
    List<PaperTradeDecisionSample> findByDecisionStatusAndCreatedAtAfterOrderByCreatedAtAsc(
            String decisionStatus, LocalDateTime cutoff);

    /** #124 — Retention prune by createdAt. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM PaperTradeDecisionSample d WHERE d.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@org.springframework.data.repository.query.Param("cutoff") LocalDateTime cutoff);
}
