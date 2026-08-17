package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.ExperimentCollection;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ExperimentCollectionRepository extends JpaRepository<ExperimentCollection, Long> {
    List<ExperimentCollection> findAllByOrderByUpdatedAtDesc();
}
