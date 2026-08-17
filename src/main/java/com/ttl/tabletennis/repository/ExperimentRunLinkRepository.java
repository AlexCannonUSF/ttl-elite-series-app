package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.ExperimentRunLink;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ExperimentRunLinkRepository extends JpaRepository<ExperimentRunLink, Long> {
    List<ExperimentRunLink> findByExperimentIdOrderByLinkedAtAsc(Long experimentId);
    Optional<ExperimentRunLink> findByExperimentIdAndSessionId(Long experimentId, Long sessionId);
}
