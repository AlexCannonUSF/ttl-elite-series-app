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
}
