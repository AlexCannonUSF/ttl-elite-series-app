package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.TrackedMatchObservation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TrackedMatchObservationRepository extends JpaRepository<TrackedMatchObservation, Long> {

    Optional<TrackedMatchObservation> findTopByBetIdOrderByObservedAtDesc(Long betId);

    Optional<TrackedMatchObservation> findTopByBetIdAndTrackedAfterCloseTrueOrderByObservedAtDesc(Long betId);

    List<TrackedMatchObservation> findByBetIdOrderByObservedAtAsc(Long betId);

    List<TrackedMatchObservation> findByEventKeyOrderByObservedAtAsc(String eventKey);

    List<TrackedMatchObservation> findBySessionIdOrderByObservedAtDesc(Long sessionId, Pageable pageable);

    long countBySessionIdAndSourceKind(Long sessionId, String sourceKind);

    /** #124 — Retention prune by observedAt. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM TrackedMatchObservation o WHERE o.observedAt < :cutoff")
    int deleteByObservedAtBefore(@org.springframework.data.repository.query.Param("cutoff") java.time.LocalDateTime cutoff);
}
