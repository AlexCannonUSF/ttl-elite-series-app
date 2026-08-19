package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.PlayerRatingTs2;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PlayerRatingTs2Repository extends JpaRepository<PlayerRatingTs2, Long> {

    List<PlayerRatingTs2> findByPlayerIdOrderBySnapshotDateAsc(Long playerId);

    Optional<PlayerRatingTs2> findTopByPlayerIdAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(Long playerId,
                                                                                                    LocalDate snapshotDate);

    long countBySnapshotDate(LocalDate snapshotDate);

    /** Cascade-delete used by {@code PlayerIdentityService.mergePlayers}. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PlayerRatingTs2 r WHERE r.playerId = :playerId")
    int deleteByPlayerId(@Param("playerId") Long playerId);
}
