package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.TrackedMatchObservation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

public interface TrackedMatchObservationRepository extends JpaRepository<TrackedMatchObservation, Long> {

    Optional<TrackedMatchObservation> findTopByBetIdOrderByObservedAtDescIdDesc(Long betId);

    Optional<TrackedMatchObservation> findTopByBetIdAndTrackedAfterCloseTrueOrderByObservedAtDescIdDesc(Long betId);

    List<TrackedMatchObservation> findByBetIdOrderByObservedAtAsc(Long betId);

    List<TrackedMatchObservation> findByEventKeyOrderByObservedAtAsc(String eventKey);

    Optional<TrackedMatchObservation> findTopByEventKeyOrderByObservedAtDescIdDesc(String eventKey);

    Optional<TrackedMatchObservation> findTopBySessionIdAndEventKeyOrderByObservedAtDescIdDesc(Long sessionId, String eventKey);

    List<TrackedMatchObservation> findBySessionIdOrderByObservedAtDesc(Long sessionId, Pageable pageable);

    @Query("""
            select observation from TrackedMatchObservation observation
            where observation.sessionId = :sessionId
              and not exists (
                  select candidate.id
                  from TrackedMatchObservation candidate
                  where candidate.sessionId = observation.sessionId
                    and candidate.eventKey = observation.eventKey
                    and (candidate.observedAt > observation.observedAt
                         or (candidate.observedAt = observation.observedAt and candidate.id > observation.id))
              )
            """)
    List<TrackedMatchObservation> findLatestForEachEventBySessionId(@Param("sessionId") Long sessionId);

    List<TrackedMatchObservation> findByProvisionalResolvedAtAfterAndProvisionalCorrectIsNotNull(
            LocalDateTime threshold
    );

    long countBySessionIdAndSourceKind(Long sessionId, String sourceKind);

    /** #124 — Retention prune by observedAt. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM TrackedMatchObservation o WHERE o.observedAt < :cutoff")
    int deleteByObservedAtBefore(@org.springframework.data.repository.query.Param("cutoff") java.time.LocalDateTime cutoff);
}
