package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.PlayerRatingWl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PlayerRatingWlRepository extends JpaRepository<PlayerRatingWl, Long> {

    List<PlayerRatingWl> findByPlayerIdOrderBySnapshotDateAsc(Long playerId);

    Optional<PlayerRatingWl> findTopByPlayerIdAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(Long playerId,
                                                                                                    LocalDate snapshotDate);

    long countBySnapshotDate(LocalDate snapshotDate);

    /** Cascade-delete used by {@code PlayerIdentityService.mergePlayers}. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PlayerRatingWl r WHERE r.playerId = :playerId")
    int deleteByPlayerId(@Param("playerId") Long playerId);
}
