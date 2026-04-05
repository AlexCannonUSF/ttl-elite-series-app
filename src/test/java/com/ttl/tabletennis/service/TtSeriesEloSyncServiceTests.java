package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.domain.RatingSnapshot;
import com.ttl.tabletennis.dto.EloSyncResultDto;
import com.ttl.tabletennis.repository.PlayerRepository;
import com.ttl.tabletennis.repository.RatingSnapshotRepository;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class TtSeriesEloSyncServiceTests {

    @Autowired
    private TtSeriesEloSyncService eloSyncService;

    @Autowired
    private PlayerRepository playerRepository;

    @Autowired
    private RatingSnapshotRepository ratingSnapshotRepository;

    @Test
    void syncFromParsedRowsInsertsOnlyWhenRatingChanges() {
        Player fomin = playerRepository.save(new Player("Fomin", "Yurij"));
        Player urban = playerRepository.save(new Player("Urban", "Wojciech"));
        LocalDate today = LocalDate.now();

        RatingSnapshot existing = new RatingSnapshot();
        existing.setPlayer(fomin);
        existing.setSnapshotDate(today.minusDays(1));
        existing.setRating(585.0);
        existing.setRatingSystem("ELO");
        ratingSnapshotRepository.save(existing);

        EloSyncResultDto result = eloSyncService.syncFromParsedRows(
                List.of(
                        new TtSeriesEloSyncService.RankingRow("Fomin Yurij", 585.0),
                        new TtSeriesEloSyncService.RankingRow("Urban Wojciech", 591.0),
                        new TtSeriesEloSyncService.RankingRow("Unknown Test Player", 700.0)
                ),
                today,
                "TEST"
        );

        assertTrue(result.success());
        assertEquals(3, result.rankingRows());
        assertEquals(2, result.matchedPlayers());
        assertEquals(1, result.snapshotsInserted());
        assertEquals(0, result.snapshotsUpdated());
        assertEquals(1, result.unchangedPlayers());
        assertEquals(1, result.unresolvedPlayers());
        assertEquals(1, ratingSnapshotRepository.findByPlayerIdOrderBySnapshotDateAsc(fomin.getId()).size());
        assertEquals(1, ratingSnapshotRepository.findByPlayerIdOrderBySnapshotDateAsc(urban.getId()).size());
    }

    @Test
    void syncFromParsedRowsUpdatesSameDaySnapshotAndSupportsSwappedOrder() {
        Player player = playerRepository.save(new Player("Wojciech", "Urban"));
        LocalDate today = LocalDate.now();

        RatingSnapshot existing = new RatingSnapshot();
        existing.setPlayer(player);
        existing.setSnapshotDate(today);
        existing.setRating(580.0);
        existing.setRatingSystem("ELO");
        ratingSnapshotRepository.save(existing);

        EloSyncResultDto result = eloSyncService.syncFromParsedRows(
                List.of(new TtSeriesEloSyncService.RankingRow("Urban Wojciech", 600.0)),
                today,
                "TEST"
        );

        assertTrue(result.success());
        assertEquals(1, result.matchedPlayers());
        assertEquals(0, result.snapshotsInserted());
        assertEquals(1, result.snapshotsUpdated());
        assertEquals(0, result.unresolvedPlayers());

        RatingSnapshot refreshed = ratingSnapshotRepository
                .findByPlayerIdAndSnapshotDateAndRatingSystem(player.getId(), today, "ELO")
                .orElseThrow();
        assertEquals(600.0, refreshed.getRating());
    }

    @Test
    void parseRankingRowsParsesTableStructure() {
        String html = """
                <html><body>
                <table>
                  <tr><th>#</th><th>Player</th><th>Rating</th></tr>
                  <tr><td>1</td><td><a href='#'>Fomin Yurij</a></td><td>585</td></tr>
                  <tr><td>2</td><td>Urban Wojciech</td><td>591</td></tr>
                </table>
                </body></html>
                """;

        List<TtSeriesEloSyncService.RankingRow> rows = eloSyncService.parseRankingRows(Jsoup.parse(html));
        assertEquals(2, rows.size());
        assertEquals("Fomin Yurij", rows.get(0).playerName());
        assertEquals(585.0, rows.get(0).rating());
        assertEquals("Urban Wojciech", rows.get(1).playerName());
        assertEquals(591.0, rows.get(1).rating());
    }

    @Test
    void backfillMissingEloSnapshotsUsesGlickoThenDefaultFallback() {
        Player glickoOnly = playerRepository.save(new Player("Test", "GlickoOnly"));
        Player noRatings = playerRepository.save(new Player("Test", "NoRatings"));
        LocalDate today = LocalDate.now();

        RatingSnapshot glicko = new RatingSnapshot();
        glicko.setPlayer(glickoOnly);
        glicko.setSnapshotDate(today.minusDays(2));
        glicko.setRating(1632.5);
        glicko.setRatingDeviation(92.0);
        glicko.setVolatility(0.06);
        glicko.setRatingSystem("GLICKO2");
        ratingSnapshotRepository.save(glicko);

        int inserted = eloSyncService.backfillMissingEloSnapshots(today);
        assertEquals(2, inserted);

        RatingSnapshot glickoFallback = ratingSnapshotRepository
                .findTopByPlayerIdAndRatingSystemAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(
                        glickoOnly.getId(),
                        "ELO",
                        today
                )
                .orElseThrow();
        assertEquals(1632.5, glickoFallback.getRating());

        RatingSnapshot defaultFallback = ratingSnapshotRepository
                .findTopByPlayerIdAndRatingSystemAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(
                        noRatings.getId(),
                        "ELO",
                        today
                )
                .orElseThrow();
        assertNotNull(defaultFallback.getRating());
    }
}
