package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.PaperTradeLearningSample;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface PaperTradeLearningSampleRepository extends JpaRepository<PaperTradeLearningSample, Long> {

    boolean existsByBetId(Long betId);

    List<PaperTradeLearningSample> findByStatusInOrderBySettledAtDesc(Collection<String> statuses, Pageable pageable);

    List<PaperTradeLearningSample> findBySettledAtAfter(LocalDateTime threshold);

    /** Used by the §5 closing-line backfill to find rows that need enrichment. */
    List<PaperTradeLearningSample> findByClosingDecimalOddsIsNullOrderBySettledAtDesc(Pageable pageable);

    /** #124 — Retention prune by settledAt. Adaptive learner reads only the last N samples
     *  (default 150) so anything beyond a 90-day window is dead weight. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM PaperTradeLearningSample s WHERE s.settledAt < :cutoff")
    int deleteBySettledAtBefore(@org.springframework.data.repository.query.Param("cutoff") LocalDateTime cutoff);
}
