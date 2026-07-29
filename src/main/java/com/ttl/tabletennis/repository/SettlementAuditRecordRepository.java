package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.SettlementAuditRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface SettlementAuditRecordRepository extends JpaRepository<SettlementAuditRecord, Long> {

    List<SettlementAuditRecord> findByBetIdOrderByDecidedAtDesc(Long betId, Pageable pageable);

    List<SettlementAuditRecord> findByTrackedEventIdOrderByDecidedAtDesc(String trackedEventId, Pageable pageable);

    List<SettlementAuditRecord> findByDecidedAtGreaterThanEqualOrderByDecidedAtDesc(LocalDateTime from, Pageable pageable);

    List<SettlementAuditRecord> findAllByOrderByDecidedAtDesc(Pageable pageable);

    Page<SettlementAuditRecord> findByDecisionOrderByDecidedAtDescIdDesc(String decision, Pageable pageable);

    List<SettlementAuditRecord> findByBetIdAndDecisionInOrderByDecidedAtDescIdDesc(Long betId,
                                                                                    Collection<String> decisions,
                                                                                    Pageable pageable);

    /** #124 — Retention prune by decidedAt (multi-KB JSON per row, grows fastest). */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM SettlementAuditRecord s WHERE s.decidedAt < :cutoff")
    int deleteByDecidedAtBefore(@org.springframework.data.repository.query.Param("cutoff") java.time.LocalDateTime cutoff);
}
