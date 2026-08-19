package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.RatingSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM RatingSnapshot r WHERE r.ratingSystem = :ratingSystem")
    int deleteByRatingSystem(@Param("ratingSystem") String ratingSystem);

    long countByRatingSystem(String ratingSystem);

    /**
     * Cascade-delete used by {@code PlayerIdentityService.mergePlayers} when
     * the source player is being collapsed into a target. The source's
     * snapshots are stale (they were calculated independently while it had
     * its own match history) and would otherwise block the source-player
     * delete on a NOT NULL foreign-key constraint.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM RatingSnapshot r WHERE r.player.id = :playerId")
    int deleteByPlayerId(@Param("playerId") Long playerId);
}
