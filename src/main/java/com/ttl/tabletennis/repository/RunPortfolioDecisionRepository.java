package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.RunPortfolioDecision;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface RunPortfolioDecisionRepository extends JpaRepository<RunPortfolioDecision, Long> {
    Optional<RunPortfolioDecision> findByOpportunityIdAndPortfolioDefinitionId(Long opportunityId, Long portfolioDefinitionId);
    List<RunPortfolioDecision> findByPortfolioDefinitionIdOrderByCapturedAtAsc(Long portfolioDefinitionId);
    long countByPortfolioDefinitionId(Long portfolioDefinitionId);
}
