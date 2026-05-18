package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.SettlementContradictionRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SettlementContradictionRecordRepository extends JpaRepository<SettlementContradictionRecord, Long> {

    long countByResolvedFalse();

    List<SettlementContradictionRecord> findByEvidenceIdOrderByObservedAtDesc(Long evidenceId, Pageable pageable);
}
