package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.PaperTradeModelCall;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaperTradeModelCallRepository extends JpaRepository<PaperTradeModelCall, Long> {

    Optional<PaperTradeModelCall> findBySessionIdAndEventKey(Long sessionId, String eventKey);

    List<PaperTradeModelCall> findBySessionIdOrderByCapturedAtDesc(Long sessionId);

    long countBySessionId(Long sessionId);
}
