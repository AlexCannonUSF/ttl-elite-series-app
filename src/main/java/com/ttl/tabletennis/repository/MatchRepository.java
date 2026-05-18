package com.ttl.tabletennis.repository;

import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.projection.HeadToHeadAggregateProjection;
import com.ttl.tabletennis.projection.PlayerStatsAggregateProjection;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MatchRepository extends JpaRepository<Match, Long> {

    Optional<Match> findByExternalId(String externalId);

    @Query("""
           SELECT m FROM Match m
           WHERE m.sourceFeedEventId = :feedEventId
              OR m.externalId = :feedEventId
           ORDER BY m.date DESC, m.id DESC
           """)
    List<Match> findMatchesByFeedEventIdentity(@Param("feedEventId") String feedEventId, Pageable pageable);

    @Query("""
           SELECT m FROM Match m
           WHERE ((m.player1.id = :p1 AND m.player2.id = :p2)
               OR  (m.player1.id = :p2 AND m.player2.id = :p1))
             AND m.date = :date
           """)
    Optional<Match> findByPlayersAndDate(@Param("p1") Long player1Id,
                                         @Param("p2") Long player2Id,
                                         @Param("date") LocalDate date);

    @Query("""
           SELECT m FROM Match m
           WHERE (m.player1.id = :p1 AND m.player2.id = :p2)
              OR (m.player1.id = :p2 AND m.player2.id = :p1)
           ORDER BY m.date DESC, m.id DESC
           """)
    List<Match> findRecentMatchesByPlayers(@Param("p1") Long player1Id,
                                           @Param("p2") Long player2Id,
                                           Pageable pageable);

    @Query("""
           SELECT m FROM Match m
           WHERE m.player1.id = :playerId OR m.player2.id = :playerId
           ORDER BY m.date DESC, m.id DESC
           """)
    List<Match> findRecentMatchesByPlayerId(@Param("playerId") Long playerId, Pageable pageable);

    @Query("""
           SELECT m FROM Match m
           WHERE m.complete = TRUE
             AND (m.player1.id = :playerId OR m.player2.id = :playerId)
             AND m.date <= :asOfDate
           ORDER BY m.date DESC, m.id DESC
           """)
    List<Match> findCompletedRecentMatchesByPlayerUpToDate(@Param("playerId") Long playerId,
                                                            @Param("asOfDate") LocalDate asOfDate,
                                                            Pageable pageable);

    @Query("""
           SELECT m FROM Match m
           WHERE m.complete = TRUE
             AND (m.player1.id = :playerId OR m.player2.id = :playerId)
             AND m.date >= :sinceDate
             AND m.date <= :asOfDate
           ORDER BY m.date DESC, m.id DESC
           """)
    List<Match> findCompletedMatchesByPlayerSince(@Param("playerId") Long playerId,
                                                   @Param("sinceDate") LocalDate sinceDate,
                                                   @Param("asOfDate") LocalDate asOfDate,
                                                   Pageable pageable);

    @Query("""
           SELECT m FROM Match m
           WHERE m.complete = TRUE
             AND ((m.player1.id = :p1 AND m.player2.id = :p2)
               OR  (m.player1.id = :p2 AND m.player2.id = :p1))
             AND m.date <= :asOfDate
           ORDER BY m.date DESC, m.id DESC
           """)
    List<Match> findCompletedRecentMatchesByPlayersUpToDate(@Param("p1") Long player1Id,
                                                             @Param("p2") Long player2Id,
                                                             @Param("asOfDate") LocalDate asOfDate,
                                                             Pageable pageable);

    @Query("""
           SELECT m FROM Match m
           WHERE m.complete = TRUE
             AND ((m.player1.id = :p1 AND m.player2.id = :p2)
               OR  (m.player1.id = :p2 AND m.player2.id = :p1))
             AND m.date >= :fromDate
           ORDER BY m.date ASC, m.id ASC
           """)
    List<Match> findCompletedMatchesByPlayersSince(@Param("p1") Long player1Id,
                                                   @Param("p2") Long player2Id,
                                                   @Param("fromDate") LocalDate fromDate);

    @Query("""
           SELECT m FROM Match m
           WHERE m.complete = TRUE
             AND m.date >= :fromDate
             AND m.date <= :toDate
           ORDER BY m.date ASC, m.id ASC
           """)
    List<Match> findCompletedMatchesBetween(@Param("fromDate") LocalDate fromDate,
                                            @Param("toDate") LocalDate toDate);

    @Query("SELECT MIN(m.date) FROM Match m WHERE m.complete = TRUE")
    LocalDate findFirstCompletedMatchDate();

    @Query("SELECT MAX(m.date) FROM Match m WHERE m.complete = TRUE")
    LocalDate findLastCompletedMatchDate();

    @Query(value = """
            SELECT p.id AS playerId,
                   p.first_name AS firstName,
                   p.last_name AS lastName,
                   COALESCE(stats.matches, 0) AS matches,
                   COALESCE(stats.wins, 0) AS wins,
                   COALESCE(stats.losses, 0) AS losses
            FROM players p
            LEFT JOIN (
                SELECT pm.player_id,
                       COUNT(*) AS matches,
                       SUM(CASE WHEN pm.winner_player_id = pm.player_id THEN 1 ELSE 0 END) AS wins,
                       SUM(CASE WHEN pm.winner_player_id IS NOT NULL AND pm.winner_player_id <> pm.player_id THEN 1 ELSE 0 END) AS losses
                FROM (
                    SELECT m.player1_id AS player_id, m.winner_player_id
                    FROM matches m
                    WHERE m.is_complete = TRUE
                    UNION ALL
                    SELECT m.player2_id AS player_id, m.winner_player_id
                    FROM matches m
                    WHERE m.is_complete = TRUE
                ) pm
                GROUP BY pm.player_id
            ) stats ON stats.player_id = p.id
            ORDER BY p.last_name ASC, p.first_name ASC
            """, nativeQuery = true)
    List<PlayerStatsAggregateProjection> aggregatePlayerStatistics();

    @Query(value = """
            SELECT COALESCE(SUM(CASE WHEN m.winner_player_id = :player1Id THEN 1 ELSE 0 END), 0) AS player1Wins,
                   COALESCE(SUM(CASE WHEN m.winner_player_id = :player2Id THEN 1 ELSE 0 END), 0) AS player2Wins,
                   COUNT(*) AS totalMatches
            FROM matches m
            WHERE m.is_complete = TRUE
              AND ((m.player1_id = :player1Id AND m.player2_id = :player2Id)
               OR  (m.player1_id = :player2Id AND m.player2_id = :player1Id))
            """, nativeQuery = true)
    HeadToHeadAggregateProjection summarizeHeadToHead(@Param("player1Id") Long player1Id,
                                                      @Param("player2Id") Long player2Id);

    long countByPlayer1IdOrPlayer2Id(Long player1Id, Long player2Id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Match m SET m.player1 = :target WHERE m.player1 = :source")
    int reassignPlayer1(@Param("source") Player source, @Param("target") Player target);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Match m SET m.player2 = :target WHERE m.player2 = :source")
    int reassignPlayer2(@Param("source") Player source, @Param("target") Player target);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Match m SET m.winnerPlayerId = :targetId WHERE m.winnerPlayerId = :sourceId")
    int reassignWinner(@Param("sourceId") Long sourceId, @Param("targetId") Long targetId);
}
