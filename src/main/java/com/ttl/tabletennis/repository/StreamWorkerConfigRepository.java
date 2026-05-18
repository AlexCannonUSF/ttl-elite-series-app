package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.StreamWorkerConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StreamWorkerConfigRepository extends JpaRepository<StreamWorkerConfig, String> {

    long countByLastState(String lastState);

    List<StreamWorkerConfig> findByLastStateOrderByUpdatedAtUtcDesc(String lastState);
}
