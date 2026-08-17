package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.ReplayDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReplayDefinitionRepository extends JpaRepository<ReplayDefinition, Long> {
    List<ReplayDefinition> findAllByOrderByCreatedAtDesc();
    Optional<ReplayDefinition> findByDefinitionChecksum(String checksum);
}
