package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.SettlementPolicyAuditRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SettlementPolicyAuditRecordRepository extends JpaRepository<SettlementPolicyAuditRecord, Long> {

    List<SettlementPolicyAuditRecord> findByPolicyNameOrderByReloadedAtDesc(String policyName, Pageable pageable);
}
