package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.SettlementDiffLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;

public interface SettlementDiffLogRepository extends JpaRepository<SettlementDiffLog, Long> {

    long countByDiffKind(String diffKind);

    /** Used by the §11 soak monitor to count fresh contradictions since soak start. */
    long countByDiffKindAndDecidedAtAfter(String diffKind, LocalDateTime threshold);

    Page<SettlementDiffLog> findAllByOrderByDecidedAtDescIdDesc(Pageable pageable);

    Page<SettlementDiffLog> findByDiffKindOrderByDecidedAtDescIdDesc(String diffKind, Pageable pageable);

    Page<SettlementDiffLog> findByDiffKindNotOrderByDecidedAtDescIdDesc(String diffKind, Pageable pageable);

    @Query("""
            select row
            from SettlementDiffLog row
            where row.newReason in :reasons
            order by row.decidedAt desc, row.id desc
            """)
    Page<SettlementDiffLog> findByNewReasonInOrderByDecidedAtDescIdDesc(@Param("reasons") Collection<String> reasons,
                                                                         Pageable pageable);

    /** #124 — Retention prune by decidedAt. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM SettlementDiffLog s WHERE s.decidedAt < :cutoff")
    int deleteByDecidedAtBefore(@Param("cutoff") java.time.LocalDateTime cutoff);
}
