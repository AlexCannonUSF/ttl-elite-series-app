package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.dto.WengLinMatchupDto;
import com.ttl.tabletennis.dto.WengLinRatingDto;
import com.ttl.tabletennis.dto.WengLinRebuildDto;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.PlayerRatingWlRepository;
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
class WengLinServiceTests {

    @Autowired
    private WengLinService wengLinService;

    @Autowired
    private PlayerRepository playerRepository;

    @Autowired
    private MatchRepository matchRepository;

    @Autowired
    private PlayerRatingWlRepository playerRatingWlRepository;

    @Test
    void rebuildWritesDailySnapshotsAndReaderReturnsLatestMatchup() {
        Player p1 = playerRepository.save(new Player("Top", "Seed"));
        Player p2 = playerRepository.save(new Player("Chaser", "Two"));

        LocalDate start = LocalDate.now().minusDays(6);
        saveMatch("wl-1", p1, p2, "3:1", start);
        saveMatch("wl-2", p1, p2, "3:0", start.plusDays(1));
        saveMatch("wl-3", p1, p2, "1:3", start.plusDays(2));
        saveMatch("wl-4", p1, p2, "3:2", start.plusDays(3));

        WengLinRebuildDto rebuilt = wengLinService.rebuild(start, start.plusDays(3));

        assertEquals(start, rebuilt.fromDate());
        assertEquals(start.plusDays(3), rebuilt.toDate());
        assertEquals(4, rebuilt.daysProcessed());
        assertEquals(2, rebuilt.playersProcessed());
        assertEquals(4, rebuilt.matchesProcessed());
        assertEquals(8, rebuilt.snapshotsWritten());
        assertEquals(8, playerRatingWlRepository.count());

        WengLinRatingDto top = wengLinService.ratingForPlayer(p1.getId(), start.plusDays(3));
        WengLinRatingDto chaser = wengLinService.ratingForPlayer(p2.getId(), start.plusDays(3));
        assertEquals(4, top.matchesSeen());
        assertEquals(3, top.wins());
        assertEquals(1, top.losses());
        assertEquals(4, chaser.matchesSeen());
        assertTrue(top.rating() > chaser.rating());

        WengLinMatchupDto matchup = wengLinService.matchup(p1.getId(), p2.getId(), start.plusDays(3));
        assertTrue(matchup.player1WinProbability() > 0.5);
        assertTrue(matchup.player1RatingDelta() > 0.0);
    }

    @Test
    void readerReturnsDefaultForExistingPlayerWithoutSnapshots() {
        Player player = playerRepository.save(new Player("Fresh", "Player"));
        LocalDate asOf = LocalDate.now();

        WengLinRatingDto rating = wengLinService.ratingForPlayer(player.getId(), asOf);

        assertNotNull(rating);
        assertEquals(player.getId(), rating.playerId());
        assertEquals(asOf, rating.snapshotDate());
        assertEquals(0, rating.matchesSeen());
        assertEquals(0.0, rating.rating(), 0.0001);
        assertEquals(1.0, rating.uncertainty(), 0.0001);
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
