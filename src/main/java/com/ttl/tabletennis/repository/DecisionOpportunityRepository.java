package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.DecisionOpportunity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DecisionOpportunityRepository extends JpaRepository<DecisionOpportunity, Long> {
    Optional<DecisionOpportunity> findBySessionIdAndEventKey(Long sessionId, String eventKey);
    List<DecisionOpportunity> findBySessionIdOrderByFirstObservedAtAsc(Long sessionId);
    long countBySessionId(Long sessionId);
}
