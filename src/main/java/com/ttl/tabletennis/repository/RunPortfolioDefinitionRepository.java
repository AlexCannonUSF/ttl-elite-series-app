package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.RunPortfolioDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface RunPortfolioDefinitionRepository extends JpaRepository<RunPortfolioDefinition, Long> {
    Optional<RunPortfolioDefinition> findBySessionIdAndPortfolioKey(Long sessionId, String portfolioKey);
    List<RunPortfolioDefinition> findBySessionIdOrderByIdAsc(Long sessionId);
}
