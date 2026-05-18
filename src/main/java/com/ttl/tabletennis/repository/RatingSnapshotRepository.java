package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.RatingSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RatingSnapshotRepository extends JpaRepository<RatingSnapshot, Long> {

    List<RatingSnapshot> findByPlayerIdOrderBySnapshotDateAsc(Long playerId);

    Optional<RatingSnapshot> findByPlayerIdAndSnapshotDateAndRatingSystem(Long playerId,
                                                                           LocalDate snapshotDate,
                                                                           String ratingSystem);

    Optional<RatingSnapshot> findTopByPlayerIdAndRatingSystemAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(Long playerId,
                                                                                                                    String ratingSystem,
                                                                                                                    LocalDate snapshotDate);

    void deleteByRatingSystem(String ratingSystem);

    long countByRatingSystem(String ratingSystem);
}
