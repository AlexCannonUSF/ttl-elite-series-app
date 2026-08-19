package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.dto.TrueSkill2MatchupDto;
import com.ttl.tabletennis.dto.TrueSkill2RatingDto;
import com.ttl.tabletennis.dto.TrueSkill2RebuildDto;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.PlayerRatingTs2Repository;
import com.ttl.tabletennis.repository.PlayerRepository;
import com.ttl.tabletennis.util.MatchResultParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class TrueSkill2ServiceTests {

    @Autowired
    private TrueSkill2Service trueSkill2Service;

    @Autowired
    private PlayerRepository playerRepository;

    @Autowired
    private MatchRepository matchRepository;

    @Autowired
    private PlayerRatingTs2Repository playerRatingTs2Repository;

    @Test
    void rebuildWritesDailySnapshotsAndReaderReturnsLatestMatchup() {
        Player p1 = playerRepository.save(new Player("Top", "Seed"));
        Player p2 = playerRepository.save(new Player("Chaser", "Two"));

        // Use a fixture-owned, valid historical window. The full Spring suite
        // intentionally reuses one in-memory context, so LocalDate.now()-based
        // fixtures can overlap matches written by another test class.
        LocalDate start = LocalDate.of(1995, 1, 10);
        long expectedPlayers = playerRepository.count();
        saveMatch("ts2-1", p1, p2, "3:1", start);
        saveMatch("ts2-2", p1, p2, "3:0", start.plusDays(1));
        saveMatch("ts2-3", p1, p2, "1:3", start.plusDays(2));
        saveMatch("ts2-4", p1, p2, "3:2", start.plusDays(3));

        TrueSkill2RebuildDto rebuilt = trueSkill2Service.rebuild(start, start.plusDays(3));

        assertEquals(start, rebuilt.fromDate());
        assertEquals(start.plusDays(3), rebuilt.toDate());
        assertEquals(4, rebuilt.daysProcessed());
        assertEquals(expectedPlayers, rebuilt.playersProcessed());
        assertEquals(4, rebuilt.matchesProcessed());
        assertEquals(expectedPlayers * 4, rebuilt.snapshotsWritten());
        assertEquals(expectedPlayers * 4, playerRatingTs2Repository.count());

        TrueSkill2RatingDto top = trueSkill2Service.ratingForPlayer(p1.getId(), start.plusDays(3));
        TrueSkill2RatingDto chaser = trueSkill2Service.ratingForPlayer(p2.getId(), start.plusDays(3));
        assertEquals(4, top.matchesSeen());
        assertEquals(3, top.wins());
        assertEquals(1, top.losses());
        assertEquals(4, chaser.matchesSeen());
        assertTrue(top.mu() > chaser.mu());

        TrueSkill2MatchupDto matchup = trueSkill2Service.matchup(p1.getId(), p2.getId(), start.plusDays(3));
        assertTrue(matchup.player1WinProbability() > 0.5);
        assertTrue(matchup.player1MuDelta() > 0.0);
    }

    @Test
    void readerReturnsDefaultForExistingPlayerWithoutSnapshots() {
        Player player = playerRepository.save(new Player("Fresh", "Player"));
        LocalDate asOf = LocalDate.now();

        TrueSkill2RatingDto rating = trueSkill2Service.ratingForPlayer(player.getId(), asOf);

        assertNotNull(rating);
        assertEquals(player.getId(), rating.playerId());
        assertEquals(asOf, rating.snapshotDate());
        assertEquals(0, rating.matchesSeen());
        assertEquals(25.0, rating.mu(), 0.0001);
        assertEquals(25.0 / 3.0, rating.sigma(), 0.0001);
    }

    private void saveMatch(String externalId, Player p1, Player p2, String result, LocalDate date) {
        Match match = new Match();
        match.setExternalId(externalId);
        match.setDate(date);
        match.setPlayer1(p1);
        match.setPlayer2(p2);
        MatchResultParser.applyToMatch(match, result);
        matchRepository.save(match);
    }
}
