package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.RunModelLaneDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface RunModelLaneDefinitionRepository extends JpaRepository<RunModelLaneDefinition, Long> {
    Optional<RunModelLaneDefinition> findBySessionIdAndLaneKey(Long sessionId, String laneKey);
    List<RunModelLaneDefinition> findBySessionIdOrderByOrdinalPositionAsc(Long sessionId);
}
