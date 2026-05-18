package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.PaperTradeDecisionSample;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaperTradeDecisionSampleRepository extends JpaRepository<PaperTradeDecisionSample, Long> {

    long countBySessionId(Long sessionId);

    long countBySessionIdAndDecisionStatus(Long sessionId, String decisionStatus);

    long countBySessionIdAndFallbackPickTrueAndDecisionStatus(Long sessionId, String decisionStatus);

    List<PaperTradeDecisionSample> findBySessionIdOrderByCreatedAtAsc(Long sessionId);

    List<PaperTradeDecisionSample> findBySessionIdOrderByCreatedAtDesc(Long sessionId, Pageable pageable);
}
