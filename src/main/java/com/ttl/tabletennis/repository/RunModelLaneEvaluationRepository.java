package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.RunModelLaneEvaluation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface RunModelLaneEvaluationRepository extends JpaRepository<RunModelLaneEvaluation, Long> {
    Optional<RunModelLaneEvaluation> findByOpportunityIdAndLaneDefinitionId(Long opportunityId, Long laneDefinitionId);
    List<RunModelLaneEvaluation> findByOpportunityIdOrderByLaneDefinitionIdAsc(Long opportunityId);
    List<RunModelLaneEvaluation> findByLaneDefinitionIdOrderByCapturedAtAsc(Long laneDefinitionId);
    long countByLaneDefinitionId(Long laneDefinitionId);
}
