package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.ValueOpportunity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ValueOpportunityRepository extends JpaRepository<ValueOpportunity, Long> {

    List<ValueOpportunity> findByStrategyOrderByCreatedAtDescEdgeDesc(String strategy, Pageable pageable);

    List<ValueOpportunity> findAllByOrderByCreatedAtDescEdgeDesc(Pageable pageable);

    void deleteByCreatedAtBefore(LocalDateTime cutoff);
}
