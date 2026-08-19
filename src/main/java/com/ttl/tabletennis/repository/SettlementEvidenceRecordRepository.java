package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.SettlementEvidenceRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SettlementEvidenceRecordRepository extends JpaRepository<SettlementEvidenceRecord, Long> {

    Optional<SettlementEvidenceRecord> findFirstByBetIdAndBundleAsOf(Long betId, LocalDateTime bundleAsOf);

    Optional<SettlementEvidenceRecord> findByEvidenceFingerprint(String evidenceFingerprint);

    Optional<SettlementEvidenceRecord> findTopByBetIdOrderByBundleAsOfDesc(Long betId);

    List<SettlementEvidenceRecord> findByTrackedEventIdOrderByBundleAsOfDesc(String trackedEventId, Pageable pageable);
}
