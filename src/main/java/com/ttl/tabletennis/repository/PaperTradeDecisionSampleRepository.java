package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.PaperTradeDecisionSample;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface PaperTradeDecisionSampleRepository extends JpaRepository<PaperTradeDecisionSample, Long> {

    interface DecisionSummary {
        String getDecisionStatus();

        Boolean getFallbackPick();

        long getRowCount();

        long getSelectionScoreCount();

        Double getSelectionScoreSum();

        long getSignalQualityCount();

        Double getSignalQualitySum();

        long getSuggestedEdgeCount();

        Double getSuggestedEdgeSum();
    }

    interface SkipReasonSummary {
        String getDecisionReason();

        long getRowCount();
    }

    long countBySessionId(Long sessionId);

    long countBySessionIdAndDecisionStatus(Long sessionId, String decisionStatus);

    long countBySessionIdAndFallbackPickTrueAndDecisionStatus(Long sessionId, String decisionStatus);

    List<PaperTradeDecisionSample> findBySessionIdOrderByCreatedAtAsc(Long sessionId);

    /**
     * Compact session telemetry projection. Keeping the aggregation in SQL
     * avoids hydrating every historical decision sample on each live-board
     * poll while preserving the exact weighted averages used by the UI.
     */
    @Query("""
            select d.decisionStatus as decisionStatus,
                   d.fallbackPick as fallbackPick,
                   count(d.id) as rowCount,
                   count(d.selectionScore) as selectionScoreCount,
                   coalesce(sum(d.selectionScore), 0.0) as selectionScoreSum,
                   count(d.signalQuality) as signalQualityCount,
                   coalesce(sum(d.signalQuality), 0.0) as signalQualitySum,
                   count(d.suggestedEdge) as suggestedEdgeCount,
                   coalesce(sum(d.suggestedEdge), 0.0) as suggestedEdgeSum
              from PaperTradeDecisionSample d
             where d.sessionId = :sessionId
             group by d.decisionStatus, d.fallbackPick
            """)
    List<DecisionSummary> summarizeBySessionId(Long sessionId);

    @Query("""
            select d.decisionReason as decisionReason, count(d.id) as rowCount
              from PaperTradeDecisionSample d
             where d.sessionId = :sessionId
               and upper(d.decisionStatus) = 'SKIPPED'
             group by d.decisionReason
            """)
    List<SkipReasonSummary> summarizeSkipReasonsBySessionId(Long sessionId);

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
