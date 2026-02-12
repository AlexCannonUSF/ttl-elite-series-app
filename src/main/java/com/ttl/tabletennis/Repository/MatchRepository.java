package com.ttl.tabletennis.Repository;

import com.ttl.tabletennis.Entity.Match;
import com.ttl.tabletennis.Entity.Player;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MatchRepository extends JpaRepository<Match, Long> {

    Optional<Match> findByExternalId(String externalId);

    /** Used by MatchupService.addMatchup(...) */
    @Query("""
           select m from Match m
           where ((m.player1.id = :p1 and m.player2.id = :p2)
               or  (m.player1.id = :p2 and m.player2.id = :p1))
             and m.date = :date
           """)
    Optional<Match> findByPlayersAndDate(@Param("p1") Long player1Id,
                                         @Param("p2") Long player2Id,
                                         @Param("date") LocalDate date);

    @Query("""
           select m from Match m
           where (m.player1.id = :p1 and m.player2.id = :p2)
              or (m.player1.id = :p2 and m.player2.id = :p1)
           """)
    List<Match> findMatchesByPlayers(@Param("p1") Long player1Id,
                                     @Param("p2") Long player2Id);

    @Query("""
           select m from Match m
           where m.player1 = :player or m.player2 = :player
           order by m.date desc
           """)
    List<Match> findMatchesByPlayer(@Param("player") Player player);
}