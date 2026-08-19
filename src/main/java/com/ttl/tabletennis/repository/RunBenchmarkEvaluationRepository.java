package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.RunBenchmarkEvaluation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RunBenchmarkEvaluationRepository extends JpaRepository<RunBenchmarkEvaluation, Long> {
    Optional<RunBenchmarkEvaluation> findByOpportunityIdAndBenchmarkKey(Long opportunityId, String benchmarkKey);
    List<RunBenchmarkEvaluation> findByOpportunityIdIn(Collection<Long> opportunityIds);
}
