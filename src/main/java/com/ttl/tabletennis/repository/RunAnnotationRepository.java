package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.RunAnnotation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RunAnnotationRepository extends JpaRepository<RunAnnotation, Long> {
    List<RunAnnotation> findBySessionIdOrderByCreatedAtDesc(Long sessionId);
}
