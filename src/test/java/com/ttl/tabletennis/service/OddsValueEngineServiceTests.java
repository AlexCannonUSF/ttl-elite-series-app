package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.dto.LiveOddsRecommendationDto;
import com.ttl.tabletennis.dto.LiveScoreSnapshotDto;
import com.ttl.tabletennis.dto.MatchupAnalysisDto;
import com.ttl.tabletennis.dto.MatchupFeatureVectorDto;
import com.ttl.tabletennis.dto.OddsRefreshResultDto;
import com.ttl.tabletennis.model.MatchOdds;
import com.ttl.tabletennis.repository.OddsQuoteRepository;
import com.ttl.tabletennis.repository.PlayerRepository;
import com.ttl.tabletennis.repository.ValueOpportunityRepository;
import com.ttl.tabletennis.scrape.HardRockOddsScraper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest
@Transactional
class OddsValueEngineServiceTests {

    @Autowired
    private OddsValueEngineService oddsValueEngineService;

    @Autowired
    private PlayerRepository playerRepository;

    @Autowired
    private OddsQuoteRepository oddsQuoteRepository;

    @Autowired
    private ValueOpportunityRepository valueOpportunityRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private PredictionFacade predictionFacade;

    @MockBean
    private PlayerIdentityService playerIdentityService;

    @MockBean
    private HardRockOddsScraper hardRockOddsScraper;

    @BeforeEach
    void clearScrapeCacheBetweenTests() {
        // The OddsValueEngineService bean is a singleton in @SpringBootTest, so its
        // internal 5s scrape cache leaks across @Test methods (each test sets a
        // different mock return). Reset it before every test so each test's
        // when(hardRockOddsScraper.fetch()) hook is actually exercised.
        oddsValueEngineService.clearScrapeCacheForTest();
    }

    @Test
    void refreshFromQuotesPersistsValueOpportunitiesWithModelVersion() {
        Player p1 = playerRepository.save(new Player("Alpha", "One"));
        Player p2 = playerRepository.save(new Player("Beta", "Two"));

        when(playerIdentityService.findCanonicalPlayer("Alpha One")).thenReturn(Optional.of(p1));
        when(playerIdentityService.findCanonicalPlayer("Beta Two")).thenReturn(Optional.of(p2));
        when(predictionFacade.currentAdaptiveRegimeTuning(anyBoolean(), any(), anyDouble()))
                .thenReturn(new PredictionModelService.AdaptiveRegimeTuning("All Settled", 0.0, 1.0, 0.0, 0.0, 0.0));

        PredictionModelService.PredictionSnapshot snapshot = new PredictionModelService.PredictionSnapshot(
                "ENSEMBLE",
                "20260212170000-ENSEMBLE-1",
                "PLATT",
                0.72,
                0.28,
                0.66,
                0.78,
                List.of(new MatchupAnalysisDto.FeatureContributionDto("Recent Form Delta", 0.31)),
                reliabilityFeatureVector(p1.getId(), p2.getId()),
                0.61,
                0.67,
                0.64,
                0.68,
                0.69,
                0.70
        );
        when(predictionFacade.predict(eq(p1.getId()), eq(p2.getId()), any(LocalDate.class), eq("ENSEMBLE")))
                .thenReturn(snapshot);

        MatchOdds quote = new MatchOdds("Alpha One", "Beta Two", 2.20, 1.75);
        OddsRefreshResultDto result = oddsValueEngineService.refreshFromQuotes(
                "CONSERVATIVE",
                "ENSEMBLE",
                List.of(quote),
                "TEST_ODDS"
        );

        assertEquals(1, result.quotesFetched());
        assertEquals(1, result.quotesResolved());
        assertEquals(1, result.opportunitiesCreated());
        assertEquals("TEST_ODDS", result.source());
        assertEquals("20260212170000-ENSEMBLE-1", result.modelVersion());

        assertEquals(1, oddsQuoteRepository.count());
        var opportunities = valueOpportunityRepository.findAll();
        assertFalse(opportunities.isEmpty());
        assertEquals("20260212170000-ENSEMBLE-1", opportunities.get(0).getModelVersion());
        assertEquals("CONSERVATIVE", opportunities.get(0).getStrategy());
    }

    @Test
    void liveOddsRecommendationsCarryReliabilityContextIntoRationale() {
        Player p1 = playerRepository.save(new Player("Signal", "Alpha"));
        Player p2 = playerRepository.save(new Player("Signal", "Beta"));

        when(playerIdentityService.findCanonicalPlayer("Signal Alpha")).thenReturn(Optional.of(p1));
        when(playerIdentityService.findCanonicalPlayer("Signal Beta")).thenReturn(Optional.of(p2));
        when(hardRockOddsScraper.fetch()).thenReturn(List.of(new MatchOdds("Signal Alpha", "Signal Beta", 2.05, 1.82)));
        when(predictionFacade.currentAdaptiveRegimeTuning(anyBoolean(), any(), anyDouble()))
                .thenReturn(new PredictionModelService.AdaptiveRegimeTuning("Live", 0.42, 0.97, 0.01, 0.03, -0.01));
        when(predictionFacade.predict(eq(p1.getId()), eq(p2.getId()), any(LocalDate.class), eq("ENSEMBLE")))
                .thenReturn(new PredictionModelService.PredictionSnapshot(
                        "ENSEMBLE",
                        "20260404091500-ENSEMBLE-1",
                        "PLATT",
                        0.68,
                        0.32,
                        0.61,
                        0.74,
                        List.of(new MatchupAnalysisDto.FeatureContributionDto("Head-to-Head (Decayed)", 0.22)),
                        reliabilityFeatureVector(p1.getId(), p2.getId()),
                        0.60,
                        0.64,
                        0.62,
                        0.65,
                        0.66,
                        0.67
                ));

        var rows = oddsValueEngineService.liveOddsRecommendations("CONSERVATIVE", "ENSEMBLE", 10, false);

        assertEquals(1, rows.size());
        var row = rows.get(0);
        assertNotNull(row.overallReliability());
        assertNotNull(row.topTriggerReliability());
        assertNotNull(row.suggestedSideBaselineStability());
        assertNotNull(row.ratingAgreement());
        assertTrue(row.rationale().contains("Reliability:"));
        assertTrue(row.rationale().contains("overall"));
        assertTrue(row.rationale().contains("model agreement"));
        assertTrue(row.rationale().contains("Regime tuning:"));
    }

    @Test
    void liveScoreSnapshotsForEventIdsCarriesSourceMetadataFromTrackedRows() {
        Player p1 = playerRepository.save(new Player("Tracked", "Alpha"));
        Player p2 = playerRepository.save(new Player("Tracked", "Beta"));
        String externalEventId = "evt-phase00-1";

        MatchOdds tracked = new MatchOdds(
                "Tracked Alpha",
                "Tracked Beta",
                2.0,
                2.0,
                "Tracked Alpha vs Tracked Beta",
                "TT Elite Series",
                true,
                "2026-04-04T12:00:00",
                "HARD_ROCK_GQL_SCORE:FLORIDA_ONLINE|event=" + externalEventId,
                "2-1 (7-5)",
                "LIVE_MID"
        );
        tracked.setExternalEventId(externalEventId);
        tracked.setSourceType("GQL_TRACKED_EVENT");
        tracked.setSourceConfidence(0.97);
        tracked.setDisplayed(false);
        tracked.setResulted(false);
        tracked.setMatchCompleted(false);
        tracked.setSourceFeedCode("ttl-feed");
        tracked.setSourceFeedEventId("feed-123");
        tracked.setScoreDetail("11-8, 9-11");

        when(hardRockOddsScraper.fetchScoreboardByEventIds(
                argThat(ids -> ids != null && ids.contains(externalEventId))
        )).thenReturn(List.of(tracked));
        when(playerIdentityService.findCanonicalPlayer("Tracked Alpha")).thenReturn(Optional.of(p1));
        when(playerIdentityService.findCanonicalPlayer("Tracked Beta")).thenReturn(Optional.of(p2));

        List<LiveScoreSnapshotDto> rows = oddsValueEngineService.liveScoreSnapshotsForEventIds(List.of(externalEventId), 10, false);

        assertEquals(1, rows.size());
        LiveScoreSnapshotDto row = rows.get(0);
        assertEquals("GQL_TRACKED_EVENT", row.sourceType());
        assertEquals(0.97, row.sourceConfidence(), 0.0001);
        assertEquals(externalEventId, row.externalEventId());
        assertFalse(row.displayed());
        assertEquals("ttl-feed", row.sourceFeedCode());
        assertEquals("feed-123", row.sourceFeedEventId());
        assertEquals("11-8, 9-11", row.scoreDetail());
        assertNotNull(row.matchupKey());
    }

    @Test
    void liveOddsRecommendationsSkipsBrokenRowAndKeepsHealthyRows() {
        Player p1 = playerRepository.save(new Player("Healthy", "Alpha"));
        Player p2 = playerRepository.save(new Player("Healthy", "Beta"));
        Player badP1 = playerRepository.save(new Player("Broken", "Alpha"));
        Player badP2 = playerRepository.save(new Player("Broken", "Beta"));

        MatchOdds broken = new MatchOdds("Broken Alpha", "Broken Beta", 2.10, 1.78);
        MatchOdds healthy = new MatchOdds("Healthy Alpha", "Healthy Beta", 2.05, 1.82);

        when(hardRockOddsScraper.fetch()).thenReturn(List.of(broken, healthy));
        when(playerIdentityService.findCanonicalPlayer("Broken Alpha")).thenReturn(Optional.of(badP1));
        when(playerIdentityService.findCanonicalPlayer("Broken Beta")).thenReturn(Optional.of(badP2));
        when(playerIdentityService.findCanonicalPlayer("Healthy Alpha")).thenReturn(Optional.of(p1));
        when(playerIdentityService.findCanonicalPlayer("Healthy Beta")).thenReturn(Optional.of(p2));
        when(predictionFacade.currentAdaptiveRegimeTuning(anyBoolean(), any(), anyDouble()))
                .thenReturn(new PredictionModelService.AdaptiveRegimeTuning("All Settled", 0.25, 1.0, 0.0, 0.0, 0.0));
        when(predictionFacade.predict(eq(badP1.getId()), eq(badP2.getId()), any(LocalDate.class), eq("ENSEMBLE")))
                .thenThrow(new IllegalStateException("broken test row"));
        when(predictionFacade.predict(eq(p1.getId()), eq(p2.getId()), any(LocalDate.class), eq("ENSEMBLE")))
                .thenReturn(new PredictionModelService.PredictionSnapshot(
                        "ENSEMBLE",
                        "20260404091500-ENSEMBLE-1",
                        "PLATT",
                        0.68,
                        0.32,
                        0.61,
                        0.74,
                        List.of(new MatchupAnalysisDto.FeatureContributionDto("Recent Form Delta", 0.22)),
                        reliabilityFeatureVector(p1.getId(), p2.getId()),
                        0.60,
                        0.64,
                        0.62,
                        0.65,
                        0.66,
                        0.67
                ));

        List<LiveOddsRecommendationDto> rows = assertDoesNotThrow(
                () -> oddsValueEngineService.liveOddsRecommendations("CONSERVATIVE", "ENSEMBLE", 10, false)
        );

        assertEquals(1, rows.size());
        assertEquals("Healthy Alpha vs Healthy Beta", rows.get(0).eventName());
    }

    @Test
    void liveOddsRecommendationsToleratesLegacyQuoteRowsWithNullLiveFlag() {
        Player p1 = playerRepository.save(new Player("Legacy", "Alpha"));
        Player p2 = playerRepository.save(new Player("Legacy", "Beta"));

        jdbcTemplate.update("""
                insert into odds_quote (
                    source,
                    player1_display,
                    player2_display,
                    player1_normalized,
                    player2_normalized,
                    event_name,
                    competition_name,
                    live_at_quote,
                    start_time_iso,
                    live_score,
                    match_phase,
                    quote_timestamp_ms,
                    american_odds_player1,
                    american_odds_player2,
                    decimal_odds_player1,
                    decimal_odds_player2,
                    scraped_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "HARD_ROCK",
                "Legacy Alpha",
                "Legacy Beta",
                "legacy alpha",
                "legacy beta",
                "Legacy Alpha vs Legacy Beta",
                "TT Elite Series",
                null,
                "2026-04-04T20:00:00Z",
                "",
                "UPCOMING",
                System.currentTimeMillis(),
                -115,
                -105,
                1.87,
                1.95,
                LocalDateTime.now().minusDays(45)
        );

        MatchOdds healthy = new MatchOdds("Legacy Alpha", "Legacy Beta", 2.05, 1.82);
        when(hardRockOddsScraper.fetch()).thenReturn(List.of(healthy));
        when(playerIdentityService.findCanonicalPlayer("Legacy Alpha")).thenReturn(Optional.of(p1));
        when(playerIdentityService.findCanonicalPlayer("Legacy Beta")).thenReturn(Optional.of(p2));
        when(predictionFacade.currentAdaptiveRegimeTuning(anyBoolean(), any(), anyDouble()))
                .thenReturn(new PredictionModelService.AdaptiveRegimeTuning("All Settled", 0.25, 1.0, 0.0, 0.0, 0.0));
        when(predictionFacade.predict(eq(p1.getId()), eq(p2.getId()), any(LocalDate.class), eq("ENSEMBLE")))
                .thenReturn(new PredictionModelService.PredictionSnapshot(
                        "ENSEMBLE",
                        "20260404091500-ENSEMBLE-1",
                        "PLATT",
                        0.63,
                        0.37,
                        0.56,
                        0.70,
                        List.of(new MatchupAnalysisDto.FeatureContributionDto("Head-to-Head (Decayed)", 0.18)),
                        reliabilityFeatureVector(p1.getId(), p2.getId()),
                        0.58,
                        0.61,
                        0.60,
                        0.62,
                        0.64,
                        0.66
                ));

        List<LiveOddsRecommendationDto> rows = assertDoesNotThrow(
                () -> oddsValueEngineService.liveOddsRecommendations("CONSERVATIVE", "ENSEMBLE", 10, false)
        );

        assertEquals(1, rows.size());
        assertEquals("Legacy Alpha vs Legacy Beta", rows.get(0).eventName());
    }

    private MatchupFeatureVectorDto reliabilityFeatureVector(Long player1Id, Long player2Id) {
        return new MatchupFeatureVectorDto(
                player1Id,
                player2Id,
                LocalDate.of(2026, 4, 4),
                0.66,
                0.34,
                0.58,
                0.54,
                new MatchupFeatureVectorDto.PlayerFeatureDto(
                        0.71,
                        0.65,
                        0.51,
                        1588,
                        1601,
                        62,
                        0.05,
                        26.7,
                        2.1,
                        0.74,
                        0.34,
                        0.63,
                        0.61,
                        0.52,
                        0.62,
                        0.57,
                        0.48,
                        0.79
                ),
                new MatchupFeatureVectorDto.PlayerFeatureDto(
                        0.49,
                        0.44,
                        0.47,
                        1512,
                        1498,
                        74,
                        0.06,
                        24.8,
                        2.4,
                        -0.22,
                        0.41,
                        0.59,
                        0.56,
                        0.49,
                        0.58,
                        0.55,
                        0.46,
                        0.72
                ),
                0.64,
                0.61,
                0.59,
                0.57,
                0.594,
                0.094,
                new MatchupFeatureVectorDto.ReliabilitySummaryDto(0.69, 0.81, 0.79, 0.72),
                new MatchupFeatureVectorDto.SignificanceSummaryDto(
                        0.63,
                        0.54,
                        0.60,
                        0.56,
                        0.47,
                        0.78,
                        1,
                        5,
                        0,
                        "Baseline Stability",
                        0.78,
                        "Schedule Strength",
                        0.47
                ),
                new MatchupFeatureVectorDto.RatingIntervalDto(1540, 1660),
                new MatchupFeatureVectorDto.RatingIntervalDto(1440, 1560)
        );
    }
}
