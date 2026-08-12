package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.PaperTradeBetShadow;
import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.domain.PaperTradeSessionShadow;
import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.domain.TrackedMatchObservation;
import com.ttl.tabletennis.dto.LiveOddsRecommendationDto;
import com.ttl.tabletennis.dto.LiveScoreSnapshotDto;
import com.ttl.tabletennis.dto.LiveStudioIntegrityDto;
import com.ttl.tabletennis.dto.PaperTradeBetDto;
import com.ttl.tabletennis.dto.PaperTradingSessionDto;
import com.ttl.tabletennis.dto.PaperTradingSyncResultDto;
import com.ttl.tabletennis.dto.TrackedMatchObservationDto;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.PaperTradeBetRepository;
import com.ttl.tabletennis.repository.PaperTradeBetShadowRepository;
import com.ttl.tabletennis.repository.PaperTradeLearningSampleRepository;
import com.ttl.tabletennis.repository.PaperTradeSessionRepository;
import com.ttl.tabletennis.repository.PaperTradeSessionShadowRepository;
import com.ttl.tabletennis.repository.PlayerRepository;
import com.ttl.tabletennis.repository.TrackedMatchObservationRepository;
import com.ttl.tabletennis.scrape.TtSeriesScraper;
import com.ttl.tabletennis.util.MatchResultParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest
@Transactional
class PaperTradingServiceTests {

    @Test
    void noVigMarketProbabilityRemovesTwoSidedOverround() {
        LiveOddsRecommendationDto row = marketRow(11L, 22L, 0.60, 0.50);

        assertEquals(0.60 / 1.10, PaperTradingService.noVigMarketProbability(row, 11L), 0.000001);
        assertEquals(0.50 / 1.10, PaperTradingService.noVigMarketProbability(row, 22L), 0.000001);
        assertNull(PaperTradingService.noVigMarketProbability(row, 33L));
    }

    @Test
    void noVigMarketProbabilityRejectsIncompleteMarket() {
        LiveOddsRecommendationDto row = marketRow(11L, 22L, 0.60, 0.0);

        assertNull(PaperTradingService.noVigMarketProbability(row, 11L));
    }

    @Test
    void accuracyGuardQuarantinesLargeNoVigMarketDisagreement() {
        Player alpha = playerRepository.save(new Player("NoVig", "Alpha"));
        Player beta = playerRepository.save(new Player("NoVig", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(120);
        LiveOddsRecommendationDto row = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "NoVig Alpha vs NoVig Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.72,
                2.08,
                -139,
                108,
                0.60,
                0.52,
                0.68,
                0.32,
                0.10,
                -0.16,
                -213,
                213,
                alpha.getName(),
                0.10,
                -213,
                0.58,
                0.76,
                true,
                "A",
                "Strong model call that contradicts the vig-free market",
                "Glicko Rating Delta",
                0.31,
                0.90,
                0.80,
                0.90,
                0.90,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );
        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(row));

        Object previousEnabled = ReflectionTestUtils.getField(paperTradingService, "accuracyGuardEnabled");
        Object previousGap = ReflectionTestUtils.getField(paperTradingService, "accuracyGuardMaxNoVigModelMarketGap");
        Object previousAgreement = ReflectionTestUtils.getField(paperTradingService, "accuracyGuardMinRatingAgreement");
        try {
            ReflectionTestUtils.setField(paperTradingService, "accuracyGuardEnabled", true);
            ReflectionTestUtils.setField(paperTradingService, "accuracyGuardMaxNoVigModelMarketGap", 0.10);
            ReflectionTestUtils.setField(paperTradingService, "accuracyGuardMinRatingAgreement", 0.65);

            PaperTradingSyncResultDto result = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

            assertEquals(0, result.betsPlaced());
            assertEquals(1, result.betsSkipped());
            assertTrue(result.session().decisionTelemetry().topSkipReasons().stream()
                    .anyMatch(reason -> "NO_VIG_MARKET_DISAGREEMENT_QUARANTINE".equals(reason.reason())));
        } finally {
            ReflectionTestUtils.setField(paperTradingService, "accuracyGuardEnabled", previousEnabled);
            ReflectionTestUtils.setField(paperTradingService, "accuracyGuardMaxNoVigModelMarketGap", previousGap);
            ReflectionTestUtils.setField(paperTradingService, "accuracyGuardMinRatingAgreement", previousAgreement);
        }
    }

    @Autowired
    private PaperTradingService paperTradingService;

    @Autowired
    private PlayerRepository playerRepository;

    @Autowired
    private MatchRepository matchRepository;

    @Autowired
    private PaperTradeBetRepository paperTradeBetRepository;

    @Autowired
    private PaperTradeSessionRepository paperTradeSessionRepository;

    @Autowired
    private PaperTradeSessionShadowRepository paperTradeSessionShadowRepository;

    @Autowired
    private PaperTradeBetShadowRepository paperTradeBetShadowRepository;

    @Autowired
    private PaperTradeLearningSampleRepository paperTradeLearningSampleRepository;

    @Autowired
    private TrackedMatchObservationRepository trackedMatchObservationRepository;

    @MockBean
    private OddsValueEngineService oddsValueEngineService;

    @MockBean
    private TtSeriesScraper ttSeriesScraper;

    @Test
    void overlappingSyncIsCoalescedWithoutTouchingTheLiveEngine() {
        AtomicBoolean guard = (AtomicBoolean) ReflectionTestUtils.getField(paperTradingService, "syncInProgress");
        assertNotNull(guard);
        guard.set(true);
        try {
            PaperTradingSyncResultDto result =
                    paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

            assertEquals("ALREADY_RUNNING", result.status());
            assertEquals(0, result.rowsScanned());
            assertNotNull(result.session());
            assertTrue(result.message().contains("already in progress"));
            verifyNoInteractions(oddsValueEngineService);
        } finally {
            guard.set(false);
        }
    }

    @Test
    void syncPlacesAndSettlesSingleLegBet() {
        Player alpha = playerRepository.save(new Player("Alpha", "One"));
        Player beta = playerRepository.save(new Player("Beta", "Two"));
        String startIso = isoDateTimeMinutesFromNow(120);

        LiveOddsRecommendationDto row = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Alpha One vs Beta Two",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                2.10,
                1.78,
                110,
                -128,
                0.46,
                0.54,
                0.58,
                0.42,
                0.12,
                -0.12,
                -138,
                138,
                alpha.getName(),
                0.12,
                -138,
                0.52,
                0.64,
                true,
                "A",
                "Recommended: Alpha One edge 12.0%",
                "Recent Form Delta",
                0.31,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(row));

        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.rowsScanned());
        assertEquals(1, first.betsPlaced());
        assertEquals(0, first.betsSettled());
        assertEquals(1, first.session().openBets());
        backdateAllOpenBetStartTimes(180);

        Match completed = new Match();
        completed.setExternalId("paper-1");
        completed.setDate(LocalDate.now());
        completed.setPlayer1(alpha);
        completed.setPlayer2(beta);
        MatchResultParser.applyToMatch(completed, "3:1");
        matchRepository.save(completed);

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of());

        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(0, second.betsPlaced());
        assertEquals(1, second.betsSettled());
        assertEquals(0, second.session().openBets());
        assertEquals(1, second.session().wins());
        assertTrue(second.session().realizedPnl() > 0.0);
        assertTrue(second.session().currentBankroll() > second.session().startingBankroll());

        List<PaperTradeBet> allBets = paperTradeBetRepository.findAll();
        assertEquals(1, allBets.size());
        assertEquals(PaperTradeBet.STATUS_WON, allBets.get(0).getStatus());

        List<PaperTradeSessionShadow> shadowSessions = paperTradeSessionShadowRepository.findAll();
        assertEquals(1, shadowSessions.size());
        assertEquals(second.session().sessionId(), shadowSessions.get(0).getSourceSessionId());
        assertFalse(shadowSessions.get(0).getCorrelationId().isBlank());

        List<PaperTradeBetShadow> shadowBets = paperTradeBetShadowRepository.findAll();
        assertEquals(1, shadowBets.size());
        assertEquals(allBets.get(0).getId(), shadowBets.get(0).getSourceBetId());
        assertEquals(PaperTradeBet.STATUS_WON, shadowBets.get(0).getStatus());
        assertFalse(shadowBets.get(0).getCorrelationId().isBlank());
    }

    @Test
    void explorationPickUsesMinimumStakeAndKeepsAuditMetadataWithinColumnLimit() {
        Player alpha = playerRepository.save(new Player("Explore", "Alpha"));
        Player beta = playerRepository.save(new Player("Explore", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(120);
        String longRationale = "Detailed model evidence. ".repeat(40);

        LiveOddsRecommendationDto row = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Explore Alpha vs Explore Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                2.10,
                1.78,
                110,
                -128,
                0.46,
                0.54,
                0.58,
                0.42,
                0.12,
                -0.12,
                -138,
                138,
                alpha.getName(),
                0.12,
                -138,
                0.52,
                0.64,
                false,
                "WATCH",
                longRationale,
                "Recent Form Delta",
                0.31,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(row));

        PaperTradingSyncResultDto result = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        assertEquals(1, result.betsPlaced());
        PaperTradeBet bet = paperTradeBetRepository.findAll().get(0);
        assertEquals(5.0, bet.getStake(), 0.0001);
        assertTrue(bet.getRationale().length() <= 512);
        assertTrue(bet.getRationale().contains("fallbackPick=true"));
        assertTrue(bet.getRationale().contains("selectionScore="));
    }

    @Test
    void resetSessionArchivesTrackedObservationsOutsideTheNewActiveRun() {
        PaperTradingSessionDto first = paperTradingService.resetSession(1000.0, "Reset A", true);
        assertNotNull(first.sessionId());

        TrackedMatchObservation observation = new TrackedMatchObservation();
        observation.setSessionId(first.sessionId());
        observation.setBetId(999L);
        observation.setEventKey("event-reset-proof");
        observation.setSource("TEST_SCORE");
        observation.setSourceKind("SCORE_FEED");
        observation.setSourceConfidence(0.9);
        observation.setTrackedAfterClose(true);
        observation.setObservedAt(LocalDateTime.now());
        trackedMatchObservationRepository.save(observation);

        LiveStudioIntegrityDto before = paperTradingService.getLiveStudioIntegrity();
        assertEquals(1, before.trackedObservations());

        PaperTradingSessionDto second = paperTradingService.resetSession(1000.0, "Reset B", true);
        assertNotNull(second.sessionId());
        assertTrue(second.sessionId() > first.sessionId());

        LiveStudioIntegrityDto after = paperTradingService.getLiveStudioIntegrity();
        assertEquals(0, after.trackedObservations());
        assertEquals(0, after.scoreFeedObservations());
        assertEquals(0, after.trackedAfterCloseObservations());
        assertEquals(2, paperTradeSessionShadowRepository.count());
        assertEquals(0, paperTradeBetShadowRepository.count());
    }

    @Test
    void sessionSnapshotPrefersNewestActiveSession() {
        paperTradingService.resetSession(1000.0, "Baseline", true);

        PaperTradeSession older = saveActiveSession("Older Active", 750.0);
        PaperTradeSession newer = saveActiveSession("Newer Active", 1250.0);

        PaperTradingSessionDto snapshot = paperTradingService.getSessionSnapshot();
        assertEquals(newer.getId(), snapshot.sessionId());
        assertEquals("Newer Active", snapshot.label());
        assertEquals(1250.0, snapshot.currentBankroll(), 0.0001);

        older.setStatus(PaperTradeSession.STATUS_CLOSED);
        paperTradeSessionRepository.save(older);

        PaperTradingSessionDto afterClose = paperTradingService.getSessionSnapshot();
        assertEquals(newer.getId(), afterClose.sessionId());
    }

    @Test
    void visibleUnpickedMatchBuildsTimelineWithoutDuplicateUnchangedRows() {
        Player alpha = playerRepository.save(new Player("Watch", "Alpha"));
        Player beta = playerRepository.save(new Player("Watch", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(10);
        String eventKey = matchupKey(alpha, beta, startIso);

        LiveOddsRecommendationDto upcoming = new LiveOddsRecommendationDto(
                "HARD_ROCK_GQL:FLORIDA_ONLINE|event=watch-1",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Watch Alpha vs Watch Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.80,
                2.00,
                -125,
                100,
                0.5556,
                0.50,
                0.54,
                0.46,
                -0.0156,
                -0.04,
                -117,
                117,
                alpha.getName(),
                -0.0156,
                -117,
                0.35,
                0.70,
                false,
                "WATCH",
                "Below executable-edge threshold",
                "Recent Form Delta",
                0.10,
                eventKey,
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );
        LiveOddsRecommendationDto live = new LiveOddsRecommendationDto(
                upcoming.source(),
                upcoming.strategy(),
                upcoming.modelVersion(),
                upcoming.eventName(),
                upcoming.competitionName(),
                true,
                upcoming.startTimeIso(),
                "0-0 (3-2)",
                "LIVE_EARLY",
                upcoming.player1Id(),
                upcoming.player1Name(),
                upcoming.player2Id(),
                upcoming.player2Name(),
                upcoming.decimalOddsPlayer1(),
                upcoming.decimalOddsPlayer2(),
                upcoming.americanOddsPlayer1(),
                upcoming.americanOddsPlayer2(),
                upcoming.impliedProbabilityPlayer1(),
                upcoming.impliedProbabilityPlayer2(),
                upcoming.modelProbabilityPlayer1(),
                upcoming.modelProbabilityPlayer2(),
                upcoming.edgePlayer1(),
                upcoming.edgePlayer2(),
                upcoming.modelFairAmericanOddsPlayer1(),
                upcoming.modelFairAmericanOddsPlayer2(),
                upcoming.suggestedSide(),
                upcoming.suggestedEdge(),
                upcoming.suggestedFairAmericanOdds(),
                upcoming.confidenceLow(),
                upcoming.confidenceHigh(),
                false,
                "WATCH",
                upcoming.rationale(),
                upcoming.topTrigger(),
                upcoming.topTriggerContribution(),
                eventKey,
                upcoming.suggestedDedupeKey()
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(upcoming), List.of(upcoming), List.of(live));
        when(oddsValueEngineService.liveScoreSnapshots(anyInt(), eq(true))).thenReturn(List.of());

        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        List<TrackedMatchObservationDto> timeline = paperTradingService.getMatchTimeline(eventKey);
        assertEquals(2, timeline.size());
        assertNull(timeline.get(0).betId());
        assertEquals("UPCOMING", timeline.get(0).matchPhase());
        assertEquals("0-0 (3-2)", timeline.get(1).liveScore());
        assertEquals("LIVE_EARLY", timeline.get(1).matchPhase());
    }

    @Test
    void syncSkipsUnsafeLongshotOdds() {
        Player alpha = playerRepository.save(new Player("Long", "ShotA"));
        Player beta = playerRepository.save(new Player("Long", "ShotB"));

        LiveOddsRecommendationDto row = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Long ShotA vs Long ShotB",
                "TTL Elite Series",
                false,
                LocalDate.now().plusDays(1).toString(),
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                5.20,
                1.25,
                420,
                -400,
                0.19,
                0.81,
                0.24,
                0.76,
                0.05,
                -0.05,
                317,
                -317,
                alpha.getName(),
                0.05,
                317,
                0.16,
                0.32,
                false,
                "C",
                "Watchlist",
                "Recent Form Delta",
                0.11,
                matchupKey(alpha, beta, LocalDate.now().plusDays(1).toString()),
                dedupeKey(alpha, beta, LocalDate.now().plusDays(1).toString(), alpha.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(row));

        PaperTradingSyncResultDto result = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, result.rowsScanned());
        assertEquals(0, result.betsPlaced());
        assertEquals(1, result.betsSkipped());
        assertEquals(0, result.session().totalBets());
    }

    @Test
    void syncBuildsDecisionTelemetryForPlacedAndSkippedCandidates() {
        Player alpha = playerRepository.save(new Player("Signal", "Alpha"));
        Player beta = playerRepository.save(new Player("Signal", "Beta"));
        Player gamma = playerRepository.save(new Player("Signal", "Gamma"));
        Player delta = playerRepository.save(new Player("Signal", "Delta"));

        String safeStartIso = isoDateTimeMinutesFromNow(120);
        String unsafeStartIso = isoDateTimeMinutesFromNow(150);

        LiveOddsRecommendationDto safeRow = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Signal Alpha vs Signal Beta",
                "TTL Elite Series",
                false,
                safeStartIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                2.08,
                1.76,
                108,
                -132,
                0.48,
                0.52,
                0.60,
                0.40,
                0.12,
                -0.12,
                -150,
                150,
                alpha.getName(),
                0.12,
                -150,
                0.54,
                0.68,
                true,
                "A",
                "Reliable primary edge",
                "Head-to-Head (Decayed)",
                0.33,
                matchupKey(alpha, beta, safeStartIso),
                dedupeKey(alpha, beta, safeStartIso, alpha.getName())
        );

        LiveOddsRecommendationDto unsafeRow = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Signal Gamma vs Signal Delta",
                "TTL Elite Series",
                false,
                unsafeStartIso,
                null,
                "UPCOMING",
                gamma.getId(),
                gamma.getName(),
                delta.getId(),
                delta.getName(),
                5.20,
                1.25,
                420,
                -400,
                0.19,
                0.81,
                0.24,
                0.76,
                0.05,
                -0.05,
                317,
                -317,
                gamma.getName(),
                0.05,
                317,
                0.16,
                0.32,
                false,
                "C",
                "Watchlist",
                "Recent Form Delta",
                0.11,
                matchupKey(gamma, delta, unsafeStartIso),
                dedupeKey(gamma, delta, unsafeStartIso, gamma.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(safeRow, unsafeRow));

        PaperTradingSyncResultDto result = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        assertEquals(1, result.betsPlaced());
        assertEquals(1, result.betsSkipped());
        assertNotNull(result.session().decisionTelemetry());
        assertEquals(2L, result.session().decisionTelemetry().consideredCount());
        assertEquals(1L, result.session().decisionTelemetry().placedCount());
        assertEquals(1L, result.session().decisionTelemetry().skippedCount());
        assertTrue(result.session().decisionTelemetry().placementRatePct() > 40.0);
        assertTrue(result.session().decisionTelemetry().avgSignalQualityPct() > 0.0);
        assertTrue(result.session().decisionTelemetry().topSkipReasons().stream()
                .anyMatch(reason -> "FAIR_ODDS_TOO_LONG".equals(reason.reason())));
    }

    @Test
    void syncAllowsWideConfidenceWhenEdgeIsStrong() {
        Player alpha = playerRepository.save(new Player("Stable", "Alpha"));
        Player beta = playerRepository.save(new Player("Stable", "Beta"));

        LiveOddsRecommendationDto row = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Stable Alpha vs Stable Beta",
                "TTL Elite Series",
                false,
                LocalDate.now().plusDays(1).toString(),
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                2.00,
                1.87,
                100,
                -115,
                0.50,
                0.50,
                0.65,
                0.35,
                0.15,
                -0.15,
                -186,
                186,
                alpha.getName(),
                0.15,
                -186,
                0.12,
                0.93,
                false,
                "B",
                "Watchlist strong edge",
                "Glicko Probability Delta",
                0.23,
                matchupKey(alpha, beta, LocalDate.now().plusDays(1).toString()),
                dedupeKey(alpha, beta, LocalDate.now().plusDays(1).toString(), alpha.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(row));

        PaperTradingSyncResultDto result = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, result.rowsScanned());
        assertEquals(1, result.betsPlaced());
        assertEquals(1, result.session().openBets());
    }

    @Test
    void terminalScoreStreamObservationIsRecordedForSkippedMatchWithoutPaperBet() {
        Player alpha = playerRepository.save(new Player("Observe", "Alpha"));
        Player beta = playerRepository.save(new Player("Observe", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(30);
        String externalEventId = "event-observe-final-1";
        String eventKey = matchupKey(alpha, beta, startIso);

        LiveOddsRecommendationDto skipped = new LiveOddsRecommendationDto(
                "HARD_ROCK_GQL:FLORIDA_ONLINE|event=" + externalEventId,
                "CONSERVATIVE", "ENSEMBLE", "Observe Alpha vs Observe Beta", "TTL Elite Series",
                false, startIso, null, "UPCOMING",
                alpha.getId(), alpha.getName(), beta.getId(), beta.getName(),
                1.70, 2.12, -143, 112, 0.59, 0.41,
                0.51, 0.49, -0.08, 0.08, -104, 104,
                beta.getName(), 0.001, 104, 0.30, 0.70,
                false, "WATCH", "Model has a lean but the wager is not approved", "Baseline",
                0.04, eventKey, dedupeKey(alpha, beta, startIso, beta.getName())
        );
        LiveScoreSnapshotDto terminal = new LiveScoreSnapshotDto(
                "HARD_ROCK_SCORE_STREAM:FLORIDA_ONLINE|event=" + externalEventId,
                "HARD_ROCK_SCORE_STREAM", 0.99, 0L,
                "Observe Alpha vs Observe Beta", "TTL Elite Series", false, startIso,
                "1-3", "FINISHED", externalEventId, false, true, true,
                "BETRADAR_UF", "sr:match:observe-final-1", "8-11, 11-9, 7-11, 9-11",
                alpha.getId(), alpha.getName(), beta.getId(), beta.getName(), eventKey
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(skipped), List.of());
        when(oddsValueEngineService.liveScoreSnapshots(anyInt(), eq(true)))
                .thenReturn(List.of(), List.of(terminal));

        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        assertEquals(0, first.betsPlaced());
        assertEquals(0, second.betsSettled());
        assertTrue(paperTradeBetRepository.findAll().isEmpty());
        TrackedMatchObservation latest = trackedMatchObservationRepository
                .findTopByEventKeyOrderByObservedAtDescIdDesc(eventKey)
                .orElseThrow();
        assertEquals("1-3", latest.getLiveScore());
        assertEquals("FINISHED", latest.getMatchPhase());
        assertEquals("SCORE_FEED", latest.getSourceKind());
        assertTrue(latest.isResulted());
        assertTrue(latest.isMatchCompleted());
        assertEquals(externalEventId, latest.getExternalEventId());
    }

    @Test
    void syncSkipsThinSignalPlusMoneyDespiteModestPositiveEdge() {
        Player alpha = playerRepository.save(new Player("Thin", "Signal"));
        Player beta = playerRepository.save(new Player("Thin", "Opponent"));

        LiveOddsRecommendationDto row = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Thin Signal vs Thin Opponent",
                "TTL Elite Series",
                false,
                LocalDate.now().plusDays(1).toString(),
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                2.45,
                1.60,
                145,
                -167,
                0.408,
                0.625,
                0.46,
                0.54,
                0.052,
                -0.085,
                117,
                -117,
                alpha.getName(),
                0.052,
                117,
                0.18,
                0.73,
                true,
                "C",
                "Thin plus-money watchlist",
                "Head-to-Head (Decayed)",
                0.04,
                matchupKey(alpha, beta, LocalDate.now().plusDays(1).toString()),
                dedupeKey(alpha, beta, LocalDate.now().plusDays(1).toString(), alpha.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(row));

        PaperTradingSyncResultDto result = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, result.rowsScanned());
        assertEquals(0, result.betsPlaced());
        assertEquals(1, result.betsSkipped());
        assertEquals(0, result.session().openBets());
    }

    @Test
    void syncSkipsPastOrLiveRowsWhenUpcomingOnlyEnabled() {
        Player alpha = playerRepository.save(new Player("Clock", "Alpha"));
        Player beta = playerRepository.save(new Player("Clock", "Beta"));

        LiveOddsRecommendationDto liveRow = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Clock Alpha vs Clock Beta Live",
                "TTL Elite Series",
                true,
                LocalDate.now().plusDays(1).toString(),
                "1-0",
                "LIVE_MID",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                2.05,
                1.82,
                105,
                -122,
                0.47,
                0.53,
                0.59,
                0.41,
                0.12,
                -0.12,
                -144,
                144,
                alpha.getName(),
                0.12,
                -144,
                0.52,
                0.65,
                true,
                "A",
                "Would qualify except live",
                "Recent Form Delta",
                0.2,
                matchupKey(alpha, beta, LocalDate.now().plusDays(1).toString()),
                dedupeKey(alpha, beta, LocalDate.now().plusDays(1).toString(), alpha.getName())
        );

        LiveOddsRecommendationDto pastRow = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Clock Alpha vs Clock Beta Past",
                "TTL Elite Series",
                false,
                LocalDate.now().minusDays(2).toString(),
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                2.00,
                1.84,
                100,
                -119,
                0.49,
                0.51,
                0.61,
                0.39,
                0.12,
                -0.12,
                -156,
                156,
                alpha.getName(),
                0.12,
                -156,
                0.54,
                0.67,
                true,
                "A",
                "Would qualify except past start",
                "Head-to-Head Decay",
                0.21,
                matchupKey(alpha, beta, LocalDate.now().minusDays(2).toString()),
                dedupeKey(alpha, beta, LocalDate.now().minusDays(2).toString(), alpha.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(liveRow, pastRow));

        PaperTradingSyncResultDto result = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(2, result.rowsScanned());
        assertEquals(0, result.betsPlaced());
        assertEquals(2, result.betsSkipped());
        assertEquals(0, result.session().openBets());
    }

    @Test
    void syncOnlyPlacesOneBetPerMatchupEvenIfBothSidesRecommended() {
        Player alpha = playerRepository.save(new Player("Dual", "Alpha"));
        Player beta = playerRepository.save(new Player("Dual", "Beta"));
        String startIso = LocalDate.now().plusDays(1).toString();
        String eventKey = matchupKey(alpha, beta, startIso);

        LiveOddsRecommendationDto alphaSide = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Dual Alpha vs Dual Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.95,
                1.95,
                -105,
                -105,
                0.50,
                0.50,
                0.58,
                0.42,
                0.08,
                -0.08,
                -138,
                138,
                alpha.getName(),
                0.08,
                -138,
                0.52,
                0.63,
                true,
                "B",
                "Alpha side qualifies",
                "Head-to-Head Decay",
                0.18,
                eventKey,
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        LiveOddsRecommendationDto betaSide = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Dual Alpha vs Dual Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.95,
                1.95,
                -105,
                -105,
                0.50,
                0.50,
                0.42,
                0.58,
                -0.08,
                0.10,
                138,
                -138,
                beta.getName(),
                0.10,
                -122,
                0.55,
                0.68,
                true,
                "A",
                "Beta side stronger edge",
                "Recent Form Delta",
                0.27,
                eventKey,
                dedupeKey(alpha, beta, startIso, beta.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(alphaSide, betaSide));

        PaperTradingSyncResultDto result = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(2, result.rowsScanned());
        assertEquals(1, result.betsPlaced());
        assertEquals(1, result.session().openBets());
        assertEquals(1, paperTradeBetRepository.findAll().size());
        PaperTradeBet only = paperTradeBetRepository.findAll().get(0);
        assertEquals(eventKey, only.getEventKey());
    }

    @Test
    void settlementDoesNotUseHistoricalResultsBeforePlacementDate() {
        Player alpha = playerRepository.save(new Player("Timing", "Alpha"));
        Player beta = playerRepository.save(new Player("Timing", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(120);

        LiveOddsRecommendationDto row = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Timing Alpha vs Timing Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                2.05,
                1.80,
                105,
                -125,
                0.47,
                0.53,
                0.59,
                0.41,
                0.12,
                -0.12,
                -144,
                144,
                alpha.getName(),
                0.12,
                -144,
                0.53,
                0.65,
                true,
                "A",
                "Timing-aware recommendation",
                "Schedule Strength Delta",
                0.22,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(row));

        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.betsPlaced());
        backdateAllOpenBetStartTimes(180);

        Match oldMatch = new Match();
        oldMatch.setExternalId("timing-old");
        oldMatch.setDate(LocalDate.now().minusDays(1));
        oldMatch.setPlayer1(alpha);
        oldMatch.setPlayer2(beta);
        MatchResultParser.applyToMatch(oldMatch, "0:3");
        matchRepository.save(oldMatch);

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of());

        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(0, second.betsSettled());
        assertEquals(1, second.session().openBets());

        Match targetMatch = new Match();
        targetMatch.setExternalId("timing-target");
        targetMatch.setDate(LocalDate.now());
        targetMatch.setPlayer1(alpha);
        targetMatch.setPlayer2(beta);
        MatchResultParser.applyToMatch(targetMatch, "3:1");
        matchRepository.save(targetMatch);

        PaperTradingSyncResultDto third = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, third.betsSettled());
        assertEquals(1, third.session().wins());
        assertEquals(0, third.session().openBets());
        PaperTradeBet settled = paperTradeBetRepository.findAll().get(0);
        assertEquals(PaperTradeBet.STATUS_WON, settled.getStatus());
        assertNotNull(settled.getResultMatchId());
        assertEquals(targetMatch.getId(), settled.getResultMatchId());
    }

    @Test
    void settlementIgnoresPriorDayResultsWhenBetWasPlacedYesterday() {
        Player alpha = playerRepository.save(new Player("Overnight", "Alpha"));
        Player beta = playerRepository.save(new Player("Overnight", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(120);

        LiveOddsRecommendationDto row = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Overnight Alpha vs Overnight Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                2.00,
                1.83,
                100,
                -120,
                0.49,
                0.51,
                0.60,
                0.40,
                0.11,
                -0.11,
                -150,
                150,
                alpha.getName(),
                0.11,
                -150,
                0.54,
                0.66,
                true,
                "A",
                "Overnight settlement guard",
                "Recent Form Delta",
                0.2,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(row));
        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        PaperTradeBet open = paperTradeBetRepository.findAll().stream()
                .filter(b -> PaperTradeBet.STATUS_OPEN.equals(b.getStatus()))
                .findFirst()
                .orElseThrow();
        open.setPlacedAt(LocalDateTime.now().minusDays(1));
        open.setStartTimeIso(LocalDate.now().atStartOfDay().plusMinutes(1).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        paperTradeBetRepository.save(open);

        Match priorDay = new Match();
        priorDay.setExternalId("overnight-prior-day");
        priorDay.setDate(LocalDate.now().minusDays(1));
        priorDay.setPlayer1(alpha);
        priorDay.setPlayer2(beta);
        MatchResultParser.applyToMatch(priorDay, "0:3");
        matchRepository.save(priorDay);

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of());
        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(0, second.betsSettled());
        assertEquals(1, second.session().openBets());

        Match todayResult = new Match();
        todayResult.setExternalId("overnight-today");
        todayResult.setDate(LocalDate.now());
        todayResult.setPlayer1(alpha);
        todayResult.setPlayer2(beta);
        MatchResultParser.applyToMatch(todayResult, "3:1");
        matchRepository.save(todayResult);

        PaperTradingSyncResultDto third = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, third.betsSettled());
        assertEquals(0, third.session().openBets());
        assertEquals(PaperTradeBet.STATUS_WON, paperTradeBetRepository.findAll().get(0).getStatus());
    }

    @Test
    void settlementDoesNotReuseSameResultMatchAcrossDifferentEvents() {
        Player alpha = playerRepository.save(new Player("Series", "Alpha"));
        Player beta = playerRepository.save(new Player("Series", "Beta"));
        String startIso1 = isoDateTimeMinutesFromNow(150);
        String startIso2 = isoDateTimeMinutesFromNow(180);

        LiveOddsRecommendationDto row1 = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Series Alpha vs Series Beta (Event 1)",
                "TTL Elite Series",
                false,
                startIso1,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                2.00,
                1.85,
                100,
                -118,
                0.49,
                0.51,
                0.60,
                0.40,
                0.11,
                -0.11,
                -150,
                150,
                alpha.getName(),
                0.11,
                -150,
                0.54,
                0.66,
                true,
                "A",
                "Event 1 pick",
                "Head-to-Head Decay",
                0.19,
                matchupKey(alpha, beta, startIso1),
                dedupeKey(alpha, beta, startIso1, alpha.getName())
        );

        LiveOddsRecommendationDto row2 = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Series Alpha vs Series Beta (Event 2)",
                "TTL Elite Series",
                false,
                startIso2,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.94,
                1.92,
                -106,
                -104,
                0.50,
                0.50,
                0.59,
                0.41,
                0.09,
                -0.09,
                -144,
                144,
                alpha.getName(),
                0.09,
                -144,
                0.53,
                0.65,
                true,
                "B",
                "Event 2 pick",
                "Recent Form Delta",
                0.21,
                matchupKey(alpha, beta, startIso2),
                dedupeKey(alpha, beta, startIso2, alpha.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(row1, row2));
        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(2, first.betsPlaced());
        assertEquals(2, first.session().openBets());
        backdateAllOpenBetStartTimes(240);

        Match firstCompleted = new Match();
        firstCompleted.setExternalId("series-1");
        firstCompleted.setDate(LocalDate.now());
        firstCompleted.setPlayer1(alpha);
        firstCompleted.setPlayer2(beta);
        MatchResultParser.applyToMatch(firstCompleted, "3:2");
        matchRepository.save(firstCompleted);

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of());

        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, second.betsSettled());
        assertEquals(1, second.session().openBets());

        PaperTradeBet remainingOpenBet = paperTradeBetRepository.findAll().stream()
                .filter(bet -> PaperTradeBet.STATUS_OPEN.equals(bet.getStatus()))
                .findFirst()
                .orElseThrow();
        remainingOpenBet.setLastSourceFeedEventId("sr:match:second-event");
        paperTradeBetRepository.save(remainingOpenBet);

        Match secondCompleted = new Match();
        secondCompleted.setExternalId("series-2");
        secondCompleted.setSourceFeedCode("BETRADAR_UF");
        secondCompleted.setSourceFeedEventId("sr:match:second-event");
        secondCompleted.setDate(LocalDate.now());
        secondCompleted.setPlayer1(alpha);
        secondCompleted.setPlayer2(beta);
        MatchResultParser.applyToMatch(secondCompleted, "3:0");
        matchRepository.save(secondCompleted);

        PaperTradingSyncResultDto third = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, third.betsSettled());
        assertEquals(0, third.session().openBets());
        assertEquals(2, third.session().wins());
    }

    @Test
    void resetArchivesOldPicksButStartsTheNewRunAtZero() {
        Player alpha = playerRepository.save(new Player("Reset", "Alpha"));
        Player beta = playerRepository.save(new Player("Reset", "Beta"));
        String startIso = LocalDate.now().plusDays(1).toString();

        LiveOddsRecommendationDto row = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Reset Alpha vs Reset Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                2.00,
                1.85,
                100,
                -118,
                0.49,
                0.51,
                0.60,
                0.40,
                0.11,
                -0.11,
                -150,
                150,
                alpha.getName(),
                0.11,
                -150,
                0.54,
                0.66,
                true,
                "A",
                "Reset scenario pick",
                "Head-to-Head Decay",
                0.19,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(row));
        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.betsPlaced());
        assertEquals(1, paperTradeBetRepository.count());

        var reset = paperTradingService.resetSession(1000.0, "Fresh Session", true);
        assertEquals(0, reset.totalBets());
        assertEquals(0, reset.openBets());
        assertTrue(reset.recentBets().isEmpty());
        assertTrue(reset.openBetsList().isEmpty());
        assertEquals(2, paperTradeSessionRepository.count());
        assertEquals(1, paperTradeBetRepository.count());
    }

    @Test
    void completedMatchLogIncludesPickedAndNonPickedMatches() {
        Player alpha = playerRepository.save(new Player("Log", "Alpha"));
        Player beta = playerRepository.save(new Player("Log", "Beta"));
        Player gamma = playerRepository.save(new Player("Log", "Gamma"));
        Player delta = playerRepository.save(new Player("Log", "Delta"));
        String startIso = isoDateTimeMinutesFromNow(120);

        LiveOddsRecommendationDto row = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Log Alpha vs Log Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                2.00,
                1.84,
                100,
                -119,
                0.49,
                0.51,
                0.61,
                0.39,
                0.12,
                -0.12,
                -156,
                156,
                alpha.getName(),
                0.12,
                -156,
                0.54,
                0.67,
                true,
                "A",
                "Logging scenario",
                "Glicko Probability Delta",
                0.26,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(row));
        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        backdateAllOpenBetStartTimes(180);

        Match pickedMatch = new Match();
        pickedMatch.setExternalId("log-picked");
        pickedMatch.setDate(LocalDate.now());
        pickedMatch.setPlayer1(alpha);
        pickedMatch.setPlayer2(beta);
        MatchResultParser.applyToMatch(pickedMatch, "3:1");
        matchRepository.save(pickedMatch);

        Match nonPickedMatch = new Match();
        nonPickedMatch.setExternalId("log-nonpicked");
        nonPickedMatch.setDate(LocalDate.now());
        nonPickedMatch.setPlayer1(gamma);
        nonPickedMatch.setPlayer2(delta);
        MatchResultParser.applyToMatch(nonPickedMatch, "3:2");
        matchRepository.save(nonPickedMatch);

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of());
        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        var logRows = paperTradingService.recentCompletedMatchesLog(7, 50);
        assertTrue(logRows.size() >= 2);
        assertTrue(logRows.stream().anyMatch(r -> r.matchId().equals(pickedMatch.getId()) && r.picked()));
        assertTrue(logRows.stream().anyMatch(r -> r.matchId().equals(nonPickedMatch.getId()) && !r.picked()));
    }

    @Test
    void futureStartTimePreventsPrematureSettlementAgainstSameDayResults() {
        Player alpha = playerRepository.save(new Player("Guard", "Alpha"));
        Player beta = playerRepository.save(new Player("Guard", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(120);

        LiveOddsRecommendationDto row = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Guard Alpha vs Guard Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                2.00,
                1.84,
                100,
                -119,
                0.49,
                0.51,
                0.61,
                0.39,
                0.12,
                -0.12,
                -156,
                156,
                alpha.getName(),
                0.12,
                -156,
                0.54,
                0.67,
                true,
                "A",
                "Settlement guard test",
                "Recent Form Delta",
                0.18,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(row));
        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.betsPlaced());

        Match sameDayResult = new Match();
        sameDayResult.setExternalId("guard-same-day");
        sameDayResult.setDate(LocalDate.now());
        sameDayResult.setPlayer1(alpha);
        sameDayResult.setPlayer2(beta);
        MatchResultParser.applyToMatch(sameDayResult, "0:3");
        matchRepository.save(sameDayResult);

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of());
        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(0, second.betsSettled());
        assertEquals(1, second.session().openBets());
        assertEquals(PaperTradeBet.STATUS_OPEN, paperTradeBetRepository.findAll().get(0).getStatus());
    }

    @Test
    void settlesFromLastObservedScoreWhenEventDisappears() {
        Player alpha = playerRepository.save(new Player("Scoreboard", "Alpha"));
        Player beta = playerRepository.save(new Player("Scoreboard", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(90);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Scoreboard Alpha vs Scoreboard Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.95,
                1.95,
                -105,
                -105,
                0.50,
                0.50,
                0.60,
                0.40,
                0.10,
                -0.10,
                -150,
                150,
                alpha.getName(),
                0.10,
                -150,
                0.55,
                0.67,
                true,
                "A",
                "Prematch placement",
                "Recent Form Delta",
                0.21,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch));
        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.betsPlaced());
        assertEquals(1, first.session().openBets());

        LiveOddsRecommendationDto liveRow = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Scoreboard Alpha vs Scoreboard Beta",
                "TTL Elite Series",
                true,
                startIso,
                "3-1",
                "LIVE_LATE",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.70,
                2.20,
                -143,
                120,
                0.56,
                0.44,
                0.64,
                0.36,
                0.08,
                -0.08,
                -178,
                178,
                alpha.getName(),
                0.08,
                -178,
                0.57,
                0.69,
                true,
                "B",
                "Live row with score",
                "Head-to-Head Decay",
                0.17,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(liveRow));
        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(0, second.betsSettled());
        assertEquals(1, second.session().openBets());
        PaperTradeBet observed = paperTradeBetRepository.findAll().get(0);
        assertEquals("3-1", observed.getLastObservedScore());
        assertNotNull(observed.getLastObservedAt());
        backdateAllOpenBetStartTimes(180);

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of());
        PaperTradingSyncResultDto third = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, third.betsSettled());
        assertEquals(0, third.session().openBets());
        assertEquals(1, third.session().wins());
        assertEquals(PaperTradeBet.STATUS_WON, paperTradeBetRepository.findAll().get(0).getStatus());
    }

    @Test
    void settlesFromDecisiveSetScoreEvenWhenFeedPhaseIsStillLive() {
        Player alpha = playerRepository.save(new Player("DecisiveLive", "Alpha"));
        Player beta = playerRepository.save(new Player("DecisiveLive", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(45);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "DecisiveLive Alpha vs DecisiveLive Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.95,
                1.95,
                -105,
                -105,
                0.50,
                0.50,
                0.60,
                0.40,
                0.10,
                -0.10,
                -150,
                150,
                alpha.getName(),
                0.10,
                -150,
                0.55,
                0.67,
                true,
                "A",
                "Prematch placement",
                "Recent Form Delta",
                0.21,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch));
        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.betsPlaced());
        assertEquals(1, first.session().openBets());
        backdateAllOpenBetStartTimes(180);

        LiveOddsRecommendationDto decisiveLiveRow = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "DecisiveLive Alpha vs DecisiveLive Beta",
                "TTL Elite Series",
                true,
                null,
                "3-1",
                "LIVE_MID",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.65,
                2.25,
                -154,
                125,
                0.58,
                0.42,
                0.66,
                0.34,
                0.08,
                -0.08,
                -194,
                194,
                alpha.getName(),
                0.08,
                -194,
                0.57,
                0.70,
                true,
                "A",
                "Decisive live score still tagged LIVE_MID",
                "Head-to-Head Decay",
                0.17,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(decisiveLiveRow));
        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        assertEquals(1, second.betsSettled());
        assertEquals(0, second.session().openBets());
        PaperTradeBet settled = paperTradeBetRepository.findAll().get(0);
        assertEquals(PaperTradeBet.STATUS_WON, settled.getStatus());
        assertEquals("SETTLED_FROM_DECISIVE_LIVE_SCORE", settled.getSettlementReason());
        assertEquals("DECISIVE_LIVE_SCORE", settled.getSettlementSource());
    }

    @Test
    void settlesUsingScoreboardSnapshotWhenOddsRowsDisappear() {
        Player alpha = playerRepository.save(new Player("Snapshot", "Alpha"));
        Player beta = playerRepository.save(new Player("Snapshot", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(45);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Snapshot Alpha vs Snapshot Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.95,
                1.95,
                -105,
                -105,
                0.50,
                0.50,
                0.60,
                0.40,
                0.10,
                -0.10,
                -150,
                150,
                alpha.getName(),
                0.10,
                -150,
                0.55,
                0.67,
                true,
                "A",
                "Prematch placement",
                "Recent Form Delta",
                0.21,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch), List.of());
        when(oddsValueEngineService.liveScoreSnapshots(anyInt(), eq(true)))
                .thenReturn(
                        List.of(),
                        List.of(new LiveScoreSnapshotDto(
                                "HARD_ROCK_GQL_SCORE:FLORIDA_ONLINE",
                                "GQL_SCOREBOARD",
                                0.90,
                                0L,
                                "Snapshot Alpha vs Snapshot Beta",
                                "TT Elite Series",
                                true,
                                null,
                                "3-1",
                                "LIVE_LATE",
                                null,
                                true,
                                false,
                                false,
                                null,
                                null,
                                null,
                                alpha.getId(),
                                alpha.getName(),
                                beta.getId(),
                                beta.getName(),
                                matchupKey(alpha, beta, startIso)
                        ))
                );

        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.betsPlaced());
        assertEquals(1, first.session().openBets());
        backdateAllOpenBetStartTimes(180);

        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, second.betsSettled());
        assertEquals(0, second.session().openBets());
        PaperTradeBet settled = paperTradeBetRepository.findAll().get(0);
        assertEquals(PaperTradeBet.STATUS_WON, settled.getStatus());
        assertEquals("SETTLED_FROM_DECISIVE_LIVE_SCORE", settled.getSettlementReason());
        assertEquals("DECISIVE_LIVE_SCORE", settled.getSettlementSource());
    }

    @Test
    void scoreSettlementFallsBackWhenBoardDisappearsWithDecisiveScore() {
        Player alpha = playerRepository.save(new Player("Margin", "Alpha"));
        Player beta = playerRepository.save(new Player("Margin", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(90);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Margin Alpha vs Margin Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.95,
                1.95,
                -105,
                -105,
                0.50,
                0.50,
                0.60,
                0.40,
                0.10,
                -0.10,
                -150,
                150,
                alpha.getName(),
                0.10,
                -150,
                0.55,
                0.67,
                true,
                "A",
                "Prematch placement",
                "Recent Form Delta",
                0.21,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        LiveOddsRecommendationDto liveRow = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Margin Alpha vs Margin Beta",
                "TTL Elite Series",
                true,
                startIso,
                "3-2",
                "LIVE_LATE",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.70,
                2.20,
                -143,
                120,
                0.56,
                0.44,
                0.64,
                0.36,
                0.08,
                -0.08,
                -178,
                178,
                alpha.getName(),
                0.08,
                -178,
                0.57,
                0.69,
                true,
                "B",
                "Live row with score",
                "Head-to-Head Decay",
                0.17,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch));
        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(liveRow));
        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        backdateAllOpenBetStartTimes(180);

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of());
        PaperTradingSyncResultDto finalSync = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        assertEquals(1, finalSync.betsSettled());
        assertEquals(0, finalSync.session().openBets());
        assertEquals(PaperTradeBet.STATUS_WON, paperTradeBetRepository.findAll().get(0).getStatus());
    }

    @Test
    void scoreSettlementUnderstandsPointStyleFinalScores() {
        Player alpha = playerRepository.save(new Player("Points", "Alpha"));
        Player beta = playerRepository.save(new Player("Points", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(90);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Points Alpha vs Points Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.95,
                1.95,
                -105,
                -105,
                0.50,
                0.50,
                0.60,
                0.40,
                0.10,
                -0.10,
                -150,
                150,
                alpha.getName(),
                0.10,
                -150,
                0.55,
                0.67,
                true,
                "A",
                "Prematch placement",
                "Recent Form Delta",
                0.21,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        LiveOddsRecommendationDto liveRow = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Points Alpha vs Points Beta",
                "TTL Elite Series",
                true,
                startIso,
                "2-2 (11-9)",
                "LIVE_LATE",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.70,
                2.20,
                -143,
                120,
                0.56,
                0.44,
                0.64,
                0.36,
                0.08,
                -0.08,
                -178,
                178,
                alpha.getName(),
                0.08,
                -178,
                0.57,
                0.69,
                true,
                "B",
                "Live row with point score",
                "Head-to-Head Decay",
                0.17,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch));
        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(liveRow));
        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        backdateAllOpenBetStartTimes(180);

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of());
        PaperTradingSyncResultDto finalSync = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        assertEquals(1, finalSync.betsSettled());
        assertEquals(0, finalSync.session().openBets());
        assertEquals(PaperTradeBet.STATUS_WON, paperTradeBetRepository.findAll().get(0).getStatus());
    }

    @Test
    void scoreSettlementDoesNotInferWinnerFromNonDecisivePointScore() {
        Player alpha = playerRepository.save(new Player("GamePoint", "Alpha"));
        Player beta = playerRepository.save(new Player("GamePoint", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(90);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "GamePoint Alpha vs GamePoint Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.95,
                1.95,
                -105,
                -105,
                0.50,
                0.50,
                0.60,
                0.40,
                0.10,
                -0.10,
                -150,
                150,
                alpha.getName(),
                0.10,
                -150,
                0.55,
                0.67,
                true,
                "A",
                "Prematch placement",
                "Recent Form Delta",
                0.21,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        LiveOddsRecommendationDto liveRow = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "GamePoint Alpha vs GamePoint Beta",
                "TTL Elite Series",
                true,
                startIso,
                "2-2 (4-7)",
                "LIVE_LATE",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.70,
                2.20,
                -143,
                120,
                0.56,
                0.44,
                0.64,
                0.36,
                0.08,
                -0.08,
                -178,
                178,
                alpha.getName(),
                0.08,
                -178,
                0.57,
                0.69,
                true,
                "B",
                "Live row with non-decisive in-set score",
                "Head-to-Head Decay",
                0.17,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch));
        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(liveRow));
        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        backdateAllOpenBetStartTimes(180);

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of());
        PaperTradingSyncResultDto finalSync = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        assertEquals(0, finalSync.betsSettled());
        assertEquals(1, finalSync.session().openBets());
        assertEquals(PaperTradeBet.STATUS_OPEN, paperTradeBetRepository.findAll().get(0).getStatus());
    }

    @Test
    void scoreSettlementDoesNotTreatInSetFragmentAsDecisiveSetResult() {
        Player alpha = playerRepository.save(new Player("SetFragment", "Alpha"));
        Player beta = playerRepository.save(new Player("SetFragment", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(90);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "SetFragment Alpha vs SetFragment Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.95,
                1.95,
                -105,
                -105,
                0.50,
                0.50,
                0.60,
                0.40,
                0.10,
                -0.10,
                -150,
                150,
                alpha.getName(),
                0.10,
                -150,
                0.55,
                0.67,
                true,
                "A",
                "Prematch placement",
                "Recent Form Delta",
                0.21,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        LiveOddsRecommendationDto liveRow = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "SetFragment Alpha vs SetFragment Beta",
                "TTL Elite Series",
                true,
                startIso,
                "2-2 (3-2)",
                "LIVE_LATE",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.82,
                2.00,
                -122,
                100,
                0.55,
                0.45,
                0.63,
                0.37,
                0.08,
                -0.08,
                -170,
                170,
                alpha.getName(),
                0.08,
                -170,
                0.57,
                0.69,
                true,
                "B",
                "Live row with in-set fragment",
                "Head-to-Head Decay",
                0.17,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch), List.of(liveRow), List.of(), List.of());
        when(oddsValueEngineService.liveScoreSnapshots(anyInt(), eq(true)))
                .thenReturn(List.of(), List.of(), List.of(), List.of());

        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        backdateAllOpenBetStartTimes(180);
        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        PaperTradingSyncResultDto fourth = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        assertEquals(0, fourth.betsSettled());
        assertEquals(1, fourth.session().openBets());
        assertEquals(PaperTradeBet.STATUS_OPEN, paperTradeBetRepository.findAll().get(0).getStatus());
    }

    @Test
    void scoreboardSnapshotRowIsPreferredWhenOddsRowHasNoScore() {
        Player alpha = playerRepository.save(new Player("SnapshotPref", "Alpha"));
        Player beta = playerRepository.save(new Player("SnapshotPref", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(70);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "SnapshotPref Alpha vs SnapshotPref Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                2.00,
                1.83,
                100,
                -120,
                0.50,
                0.50,
                0.60,
                0.40,
                0.10,
                -0.10,
                -150,
                150,
                alpha.getName(),
                0.10,
                -150,
                0.55,
                0.68,
                true,
                "A",
                "Prematch placement",
                "Recent Form Delta",
                0.21,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        LiveOddsRecommendationDto liveOddsNoScore = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "SnapshotPref Alpha vs SnapshotPref Beta",
                "TTL Elite Series",
                true,
                startIso,
                null,
                "LIVE_MID",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.75,
                2.20,
                -133,
                120,
                0.57,
                0.43,
                0.62,
                0.38,
                0.05,
                -0.05,
                -163,
                163,
                alpha.getName(),
                0.05,
                -163,
                0.49,
                0.78,
                true,
                "B",
                "Odds row without score",
                "Head-to-Head Decay",
                0.16,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        LiveScoreSnapshotDto scoreSnapshot = new LiveScoreSnapshotDto(
                "HARD_ROCK_GQL_SCORE:FLORIDA_ONLINE",
                "GQL_SCOREBOARD",
                0.90,
                0L,
                "SnapshotPref Alpha vs SnapshotPref Beta",
                "TT Elite Series",
                true,
                startIso,
                "2-2 (8-10)",
                "LIVE_LATE",
                null,
                true,
                false,
                false,
                null,
                null,
                null,
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                matchupKey(alpha, beta, startIso)
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch), List.of(liveOddsNoScore));
        when(oddsValueEngineService.liveScoreSnapshots(anyInt(), eq(true)))
                .thenReturn(List.of(), List.of(scoreSnapshot));

        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        assertEquals(0, second.betsSettled());
        PaperTradeBet openBet = paperTradeBetRepository.findAll().get(0);
        assertEquals("2-2 (8-10)", openBet.getLastObservedScore());
        assertEquals("LIVE_LATE", openBet.getLastObservedPhase());
    }

    @Test
    void settlementUsesLastObservedScoreWhenFinalPhaseHasNoScore() {
        Player alpha = playerRepository.save(new Player("FinalPhase", "Alpha"));
        Player beta = playerRepository.save(new Player("FinalPhase", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(90);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "FinalPhase Alpha vs FinalPhase Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.95,
                1.95,
                -105,
                -105,
                0.50,
                0.50,
                0.60,
                0.40,
                0.10,
                -0.10,
                -150,
                150,
                alpha.getName(),
                0.10,
                -150,
                0.55,
                0.67,
                true,
                "A",
                "Prematch placement",
                "Recent Form Delta",
                0.21,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        LiveOddsRecommendationDto liveRow = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "FinalPhase Alpha vs FinalPhase Beta",
                "TTL Elite Series",
                true,
                startIso,
                "2-2 (11-9)",
                "LIVE_LATE",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.70,
                2.20,
                -143,
                120,
                0.56,
                0.44,
                0.64,
                0.36,
                0.08,
                -0.08,
                -178,
                178,
                alpha.getName(),
                0.08,
                -178,
                0.57,
                0.69,
                true,
                "B",
                "Live row with score",
                "Head-to-Head Decay",
                0.17,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        LiveOddsRecommendationDto finalRowNoScore = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "FinalPhase Alpha vs FinalPhase Beta",
                "TTL Elite Series",
                false,
                null,
                null,
                "FINAL",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.70,
                2.20,
                -143,
                120,
                0.56,
                0.44,
                0.64,
                0.36,
                0.08,
                -0.08,
                -178,
                178,
                alpha.getName(),
                0.08,
                -178,
                0.57,
                0.69,
                false,
                "B",
                "Final row without score",
                "Head-to-Head Decay",
                0.17,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch));
        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(liveRow));
        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        backdateAllOpenBetStartTimes(180);

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(finalRowNoScore));
        PaperTradingSyncResultDto finalSync = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        assertEquals(1, finalSync.betsSettled());
        assertEquals(0, finalSync.session().openBets());
        assertEquals(PaperTradeBet.STATUS_WON, paperTradeBetRepository.findAll().get(0).getStatus());
    }

    @Test
    void settlementKeepsDecisiveLiveScoreWhenFinalRowIsAmbiguous() {
        Player alpha = playerRepository.save(new Player("FinalAmbiguous", "Alpha"));
        Player beta = playerRepository.save(new Player("FinalAmbiguous", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(90);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "FinalAmbiguous Alpha vs FinalAmbiguous Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.95,
                1.95,
                -105,
                -105,
                0.50,
                0.50,
                0.60,
                0.40,
                0.10,
                -0.10,
                -150,
                150,
                alpha.getName(),
                0.10,
                -150,
                0.55,
                0.67,
                true,
                "A",
                "Prematch placement",
                "Recent Form Delta",
                0.21,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        LiveOddsRecommendationDto liveRow = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "FinalAmbiguous Alpha vs FinalAmbiguous Beta",
                "TTL Elite Series",
                true,
                startIso,
                "2-2 (11-9)",
                "LIVE_LATE",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.70,
                2.20,
                -143,
                120,
                0.56,
                0.44,
                0.64,
                0.36,
                0.08,
                -0.08,
                -178,
                178,
                alpha.getName(),
                0.08,
                -178,
                0.57,
                0.69,
                true,
                "B",
                "Live row with decisive point score",
                "Head-to-Head Decay",
                0.17,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        LiveOddsRecommendationDto finalRowAmbiguous = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "FinalAmbiguous Alpha vs FinalAmbiguous Beta",
                "TTL Elite Series",
                false,
                null,
                "2-2",
                "FINAL",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.70,
                2.20,
                -143,
                120,
                0.56,
                0.44,
                0.64,
                0.36,
                0.08,
                -0.08,
                -178,
                178,
                alpha.getName(),
                0.08,
                -178,
                0.57,
                0.69,
                false,
                "B",
                "Final row with ambiguous set score",
                "Head-to-Head Decay",
                0.17,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch));
        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(liveRow));
        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        backdateAllOpenBetStartTimes(180);

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(finalRowAmbiguous));
        PaperTradingSyncResultDto finalSync = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        assertEquals(1, finalSync.betsSettled());
        assertEquals(0, finalSync.session().openBets());
        assertEquals(PaperTradeBet.STATUS_WON, paperTradeBetRepository.findAll().get(0).getStatus());
    }

    @Test
    void nearFinishFallbackDoesNotSettleFromIncompleteFinalSetLead() {
        Player alpha = playerRepository.save(new Player("FinalSet", "Alpha"));
        Player beta = playerRepository.save(new Player("FinalSet", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(60);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "FinalSet Alpha vs FinalSet Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                2.02,
                1.82,
                102,
                -122,
                0.49,
                0.51,
                0.40,
                0.60,
                -0.09,
                0.09,
                122,
                -122,
                beta.getName(),
                0.09,
                -122,
                0.54,
                0.69,
                true,
                "A",
                "Near-finish fallback test",
                "Head-to-Head Decay",
                0.25,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, beta.getName())
        );

        LiveOddsRecommendationDto liveNearFinish = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "FinalSet Alpha vs FinalSet Beta",
                "TTL Elite Series",
                true,
                startIso,
                "2-2 (5-10)",
                "LIVE_LATE",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                3.0,
                1.4,
                200,
                -250,
                0.33,
                0.67,
                0.25,
                0.75,
                -0.08,
                0.08,
                333,
                -333,
                beta.getName(),
                0.08,
                -333,
                0.45,
                0.78,
                true,
                "B",
                "Near-finish final set lead",
                "Recent Form Delta",
                0.19,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, beta.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch));
        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(liveNearFinish));
        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        PaperTradeBet open = paperTradeBetRepository.findAll().stream()
                .filter(b -> PaperTradeBet.STATUS_OPEN.equals(b.getStatus()))
                .findFirst()
                .orElseThrow();
        open.setStartTimeIso(LocalDateTime.now().minusHours(2).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        open.setLastObservedAt(LocalDateTime.now().minusMinutes(35));
        open.setMissingBoardCount(4);
        paperTradeBetRepository.save(open);

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of());
        PaperTradingSyncResultDto settled = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        assertEquals(0, settled.betsSettled());
        assertEquals(1, settled.session().openBets());
        PaperTradeBet finalBet = paperTradeBetRepository.findAll().get(0);
        assertEquals(PaperTradeBet.STATUS_OPEN, finalBet.getStatus());
        assertNull(finalBet.getSettlementReason());
        assertNull(finalBet.getSettlementSource());
    }

    @Test
    void officialH2hLedgerSettlesWhenSingleSameDayWinnerExists() {
        Player alpha = playerRepository.save(new Player("Ledger", "Alpha"));
        Player beta = playerRepository.save(new Player("Ledger", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(60);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Ledger Alpha vs Ledger Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                2.08,
                1.76,
                108,
                -132,
                0.48,
                0.52,
                0.39,
                0.61,
                -0.09,
                0.09,
                132,
                -132,
                beta.getName(),
                0.09,
                -132,
                0.55,
                0.72,
                true,
                "A",
                "Official ledger confirmation test",
                "Head-to-Head Decay",
                0.26,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, beta.getName())
        );

        LiveOddsRecommendationDto lateLive = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Ledger Alpha vs Ledger Beta",
                "TTL Elite Series",
                true,
                startIso,
                "2-2 (8-10)",
                "LIVE_LATE",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                2.9,
                1.44,
                190,
                -227,
                0.34,
                0.66,
                0.24,
                0.76,
                -0.10,
                0.10,
                294,
                -294,
                beta.getName(),
                0.10,
                -294,
                0.46,
                0.77,
                true,
                "B",
                "Late live incomplete score",
                "Recent Form Delta",
                0.18,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, beta.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch));
        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(lateLive));
        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        PaperTradeBet open = paperTradeBetRepository.findAll().stream()
                .filter(b -> PaperTradeBet.STATUS_OPEN.equals(b.getStatus()))
                .findFirst()
                .orElseThrow();
        open.setStartTimeIso(LocalDate.now().atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        open.setLastObservedAt(LocalDateTime.now().minusMinutes(35));
        open.setMissingBoardCount(4);
        paperTradeBetRepository.save(open);

        when(ttSeriesScraper.lookupOfficialMatchesForPair(eq(alpha.getName()), eq(beta.getName()), eq(24)))
                .thenReturn(List.of(new TtSeriesScraper.OfficialLedgerMatch(
                        "official-h2h",
                        "https://www.tt-series.com/h2h/?player_a=Ledger%20Alpha&player_b=Ledger%20Beta",
                        alpha.getName(),
                        beta.getName(),
                        "2:3",
                        LocalDate.now(),
                        beta.getName()
                )));
        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of());

        PaperTradingSyncResultDto settled = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        assertEquals(1, settled.betsSettled());
        PaperTradeBet finalBet = paperTradeBetRepository.findAll().get(0);
        assertEquals(PaperTradeBet.STATUS_WON, finalBet.getStatus());
        assertEquals("SETTLED_FROM_OFFICIAL_H2H_LEDGER", finalBet.getSettlementReason());
        assertEquals("OFFICIAL_RESULT", finalBet.getSettlementSource());
        verify(ttSeriesScraper).lookupOfficialMatchesForPair(eq(alpha.getName()), eq(beta.getName()), eq(24));
    }

    @Test
    void officialLedgerSkipsConflictingSameDayWinners() {
        Player alpha = playerRepository.save(new Player("Ambiguous", "Alpha"));
        Player beta = playerRepository.save(new Player("Ambiguous", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(60);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Ambiguous Alpha vs Ambiguous Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                2.05,
                1.80,
                105,
                -125,
                0.49,
                0.51,
                0.40,
                0.60,
                -0.09,
                0.09,
                125,
                -125,
                beta.getName(),
                0.09,
                -125,
                0.54,
                0.70,
                true,
                "A",
                "Ambiguous official ledger test",
                "Head-to-Head Decay",
                0.24,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, beta.getName())
        );

        LiveOddsRecommendationDto lateLive = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Ambiguous Alpha vs Ambiguous Beta",
                "TTL Elite Series",
                true,
                startIso,
                "2-2 (10-8)",
                "LIVE_LATE",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                2.75,
                1.48,
                175,
                -208,
                0.36,
                0.64,
                0.26,
                0.74,
                -0.10,
                0.10,
                275,
                -275,
                beta.getName(),
                0.10,
                -275,
                0.45,
                0.78,
                true,
                "B",
                "Conflicting official ledgers",
                "Recent Form Delta",
                0.19,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, beta.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch));
        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(lateLive));
        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        PaperTradeBet open = paperTradeBetRepository.findAll().stream()
                .filter(b -> PaperTradeBet.STATUS_OPEN.equals(b.getStatus()))
                .findFirst()
                .orElseThrow();
        open.setStartTimeIso(LocalDate.now().atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        open.setLastObservedAt(LocalDateTime.now().minusMinutes(35));
        open.setMissingBoardCount(4);
        paperTradeBetRepository.save(open);

        when(ttSeriesScraper.lookupOfficialMatchesForPair(eq(alpha.getName()), eq(beta.getName()), eq(24)))
                .thenReturn(List.of(
                        new TtSeriesScraper.OfficialLedgerMatch(
                                "official-h2h",
                                "https://www.tt-series.com/h2h/?player_a=Ambiguous%20Alpha&player_b=Ambiguous%20Beta",
                                alpha.getName(),
                                beta.getName(),
                                "3:2",
                                LocalDate.now(),
                                alpha.getName()
                        ),
                        new TtSeriesScraper.OfficialLedgerMatch(
                                "official-player-left",
                                "https://www.tt-series.com/player/?player=Ambiguous%20Alpha",
                                alpha.getName(),
                                beta.getName(),
                                "2:3",
                                LocalDate.now(),
                                beta.getName()
                        )
                ));
        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of());

        PaperTradingSyncResultDto settled = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        assertEquals(0, settled.betsSettled());
        PaperTradeBet finalBet = paperTradeBetRepository.findAll().get(0);
        assertEquals(PaperTradeBet.STATUS_OPEN, finalBet.getStatus());
        assertNull(finalBet.getSettlementReason());
    }

    @Test
    void staleOnBoardScoreDoesNotSettleFromIncompleteLateLead() {
        Player alpha = playerRepository.save(new Player("Locked", "Alpha"));
        Player beta = playerRepository.save(new Player("Locked", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(60);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Locked Alpha vs Locked Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                2.00,
                1.84,
                100,
                -119,
                0.50,
                0.50,
                0.39,
                0.61,
                -0.11,
                0.11,
                156,
                -156,
                beta.getName(),
                0.11,
                -156,
                0.56,
                0.70,
                true,
                "A",
                "Prematch placement for stale on-board fallback",
                "Head-to-Head Decay",
                0.21,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, beta.getName())
        );

        // Simulates a locked betting line: row remains on board with a frozen late-game score.
        LiveOddsRecommendationDto lockedLineRow = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Locked Alpha vs Locked Beta",
                "TTL Elite Series",
                false,
                null,
                "2-2 (5-10)",
                "LIVE_LATE",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                3.0,
                1.4,
                200,
                -250,
                0.33,
                0.67,
                0.25,
                0.75,
                -0.08,
                0.08,
                333,
                -333,
                beta.getName(),
                0.08,
                -333,
                0.45,
                0.79,
                true,
                "B",
                "Locked line with stale late score",
                "Recent Form Delta",
                0.19,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, beta.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch), List.of(lockedLineRow), List.of(lockedLineRow));

        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        PaperTradeBet open = paperTradeBetRepository.findAll().stream()
                .filter(b -> PaperTradeBet.STATUS_OPEN.equals(b.getStatus()))
                .findFirst()
                .orElseThrow();
        open.setStartTimeIso(LocalDateTime.now().minusHours(2).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        open.setLastObservedScore("2-2 (5-10)");
        open.setLastObservedPhase("LIVE_LATE");
        open.setLastObservedAt(LocalDateTime.now().minusMinutes(20));
        open.setMissingBoardCount(5);
        paperTradeBetRepository.save(open);

        PaperTradingSyncResultDto settled = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        assertEquals(0, settled.betsSettled());
        assertEquals(1, settled.session().openBets());
        PaperTradeBet finalBet = paperTradeBetRepository.findAll().get(0);
        assertEquals(PaperTradeBet.STATUS_OPEN, finalBet.getStatus());
        assertNull(finalBet.getSettlementReason());
        assertNull(finalBet.getSettlementSource());
    }

    @Test
    void staleOnBoardScoreDoesNotSettleWhenFeedPhaseFallsBackToUpcoming() {
        Player alpha = playerRepository.save(new Player("LockedUpcoming", "Alpha"));
        Player beta = playerRepository.save(new Player("LockedUpcoming", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(60);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "LockedUpcoming Alpha vs LockedUpcoming Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                2.00,
                1.84,
                100,
                -119,
                0.50,
                0.50,
                0.39,
                0.61,
                -0.11,
                0.11,
                156,
                -156,
                beta.getName(),
                0.11,
                -156,
                0.56,
                0.70,
                true,
                "A",
                "Prematch placement for upcoming-phase stale score",
                "Head-to-Head Decay",
                0.21,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, beta.getName())
        );

        LiveOddsRecommendationDto lockedUpcomingPhase = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "LockedUpcoming Alpha vs LockedUpcoming Beta",
                "TTL Elite Series",
                false,
                null,
                "2-2 (5-10)",
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                3.0,
                1.4,
                200,
                -250,
                0.33,
                0.67,
                0.25,
                0.75,
                -0.08,
                0.08,
                333,
                -333,
                beta.getName(),
                0.08,
                -333,
                0.45,
                0.79,
                true,
                "B",
                "Locked line with score but UPCOMING phase",
                "Recent Form Delta",
                0.19,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, beta.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch), List.of(lockedUpcomingPhase), List.of(lockedUpcomingPhase));

        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        PaperTradeBet open = paperTradeBetRepository.findAll().stream()
                .filter(b -> PaperTradeBet.STATUS_OPEN.equals(b.getStatus()))
                .findFirst()
                .orElseThrow();
        open.setStartTimeIso(LocalDateTime.now().minusHours(2).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        open.setLastObservedScore("2-2 (5-10)");
        open.setLastObservedPhase("UPCOMING");
        open.setLastObservedAt(LocalDateTime.now().minusMinutes(20));
        open.setMissingBoardCount(5);
        paperTradeBetRepository.save(open);

        PaperTradingSyncResultDto settled = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        assertEquals(0, settled.betsSettled());
        assertEquals(1, settled.session().openBets());
        PaperTradeBet finalBet = paperTradeBetRepository.findAll().get(0);
        assertEquals(PaperTradeBet.STATUS_OPEN, finalBet.getStatus());
        assertNull(finalBet.getSettlementReason());
        assertNull(finalBet.getSettlementSource());
    }

    @Test
    void staleOnBoardScoreDoesNotSettleWhenScoreDisappearsAfterLineLock() {
        Player alpha = playerRepository.save(new Player("LineLock", "Alpha"));
        Player beta = playerRepository.save(new Player("LineLock", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(55);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "LineLock Alpha vs LineLock Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                2.10,
                1.78,
                110,
                -128,
                0.46,
                0.54,
                0.39,
                0.61,
                -0.11,
                0.11,
                156,
                -156,
                beta.getName(),
                0.11,
                -156,
                0.56,
                0.70,
                true,
                "A",
                "Prematch placement for line-lock no-score fallback",
                "Head-to-Head Decay",
                0.21,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, beta.getName())
        );

        LiveOddsRecommendationDto lockedNoScore = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "LineLock Alpha vs LineLock Beta",
                "TTL Elite Series",
                false,
                null,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                2.35,
                1.62,
                135,
                -161,
                0.40,
                0.60,
                0.36,
                0.64,
                -0.04,
                0.04,
                178,
                -178,
                beta.getName(),
                0.04,
                -178,
                0.40,
                0.79,
                false,
                "C",
                "Line locked and score hidden",
                "Recent Form Delta",
                0.14,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, beta.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch), List.of(lockedNoScore));

        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        PaperTradeBet open = paperTradeBetRepository.findAll().stream()
                .filter(b -> PaperTradeBet.STATUS_OPEN.equals(b.getStatus()))
                .findFirst()
                .orElseThrow();
        open.setStartTimeIso(LocalDateTime.now().minusHours(2).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        open.setLastObservedScore("2-2 (5-10)");
        open.setLastObservedPhase("LIVE_LATE");
        open.setLastObservedAt(LocalDateTime.now().minusMinutes(20));
        open.setMissingBoardCount(5);
        paperTradeBetRepository.save(open);

        PaperTradingSyncResultDto settled = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        assertEquals(0, settled.betsSettled());
        PaperTradeBet finalBet = paperTradeBetRepository.findAll().get(0);
        assertEquals(PaperTradeBet.STATUS_OPEN, finalBet.getStatus());
        assertNull(finalBet.getSettlementReason());
        assertNull(finalBet.getSettlementSource());
    }

    @Test
    void scoreDropFromLockedLineIncrementsMissingCounterInsteadOfResetting() {
        Player alpha = playerRepository.save(new Player("ScoreDrop", "Alpha"));
        Player beta = playerRepository.save(new Player("ScoreDrop", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(65);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "ScoreDrop Alpha vs ScoreDrop Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                2.00,
                1.84,
                100,
                -119,
                0.50,
                0.50,
                0.40,
                0.60,
                -0.10,
                0.10,
                150,
                -150,
                beta.getName(),
                0.10,
                -150,
                0.55,
                0.70,
                true,
                "B",
                "Prematch placement for score-drop tracking",
                "Recent Form Delta",
                0.20,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, beta.getName())
        );

        LiveOddsRecommendationDto lockedNoScore = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "ScoreDrop Alpha vs ScoreDrop Beta",
                "TTL Elite Series",
                false,
                null,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                2.25,
                1.65,
                125,
                -154,
                0.42,
                0.58,
                0.35,
                0.65,
                -0.07,
                0.07,
                193,
                -193,
                beta.getName(),
                0.07,
                -193,
                0.48,
                0.80,
                false,
                "D",
                "Locked line with hidden score",
                "Head-to-Head Decay",
                0.16,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, beta.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch), List.of(lockedNoScore));

        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        PaperTradeBet open = paperTradeBetRepository.findAll().stream()
                .filter(b -> PaperTradeBet.STATUS_OPEN.equals(b.getStatus()))
                .findFirst()
                .orElseThrow();
        open.setLastObservedScore("2-2 (5-10)");
        open.setLastObservedPhase("LIVE_LATE");
        open.setLastObservedAt(LocalDateTime.now().minusMinutes(1));
        open.setMissingBoardCount(0);
        paperTradeBetRepository.save(open);

        PaperTradingSyncResultDto sync = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(0, sync.betsSettled());

        PaperTradeBet tracked = paperTradeBetRepository.findAll().stream()
                .filter(b -> PaperTradeBet.STATUS_OPEN.equals(b.getStatus()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, tracked.getMissingBoardCount());
        assertEquals("2-2 (5-10)", tracked.getLastObservedScore());
        assertEquals("LIVE_LATE", tracked.getLastObservedPhase());
    }

    @Test
    void settlementRespectsScoreOrientationWhenFeedPlayerOrderFlips() {
        Player alpha = playerRepository.save(new Player("Orientation", "Alpha"));
        Player beta = playerRepository.save(new Player("Orientation", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(45);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Orientation Alpha vs Orientation Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.95,
                1.95,
                -105,
                -105,
                0.50,
                0.50,
                0.60,
                0.40,
                0.10,
                -0.10,
                -150,
                150,
                alpha.getName(),
                0.10,
                -150,
                0.55,
                0.67,
                true,
                "A",
                "Prematch placement",
                "Recent Form Delta",
                0.21,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        // Feed flips player order: score "1-3" means row.player2 wins 3-1 (Alpha).
        LiveOddsRecommendationDto flippedOrderRow = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Orientation Alpha vs Orientation Beta",
                "TTL Elite Series",
                true,
                null,
                "1-3",
                "LIVE_MID",
                beta.getId(),
                beta.getName(),
                alpha.getId(),
                alpha.getName(),
                2.25,
                1.65,
                125,
                -154,
                0.42,
                0.58,
                0.34,
                0.66,
                -0.08,
                0.08,
                194,
                -194,
                alpha.getName(),
                0.08,
                -194,
                0.57,
                0.70,
                true,
                "A",
                "Flipped order decisive score",
                "Head-to-Head Decay",
                0.17,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch), List.of(flippedOrderRow));

        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.betsPlaced());
        backdateAllOpenBetStartTimes(180);

        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, second.betsSettled());
        assertEquals(0, second.session().openBets());
        PaperTradeBet settled = paperTradeBetRepository.findAll().get(0);
        assertEquals(PaperTradeBet.STATUS_WON, settled.getStatus());
        assertEquals("SETTLED_FROM_DECISIVE_LIVE_SCORE", settled.getSettlementReason());
        assertEquals("DECISIVE_LIVE_SCORE", settled.getSettlementSource());
    }

    @Test
    void missingBoardTimeoutVoidsAndRefundsOpenBet() {
        Player alpha = playerRepository.save(new Player("Void", "Alpha"));
        Player beta = playerRepository.save(new Player("Void", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(30);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Void Alpha vs Void Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.92,
                1.98,
                -108,
                -102,
                0.50,
                0.50,
                0.61,
                0.39,
                0.11,
                -0.11,
                -156,
                156,
                alpha.getName(),
                0.11,
                -156,
                0.54,
                0.67,
                true,
                "A",
                "Place a bet then disappear from board",
                "Recent Form Delta",
                0.22,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch));
        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.betsPlaced());
        backdateAllOpenBetStartTimes(300);

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of());

        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        PaperTradingSyncResultDto third = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        PaperTradingSyncResultDto fourth = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        PaperTradingSyncResultDto fifth = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        assertEquals(0, second.betsSettled());
        assertEquals(0, third.betsSettled());
        assertEquals(0, fourth.betsSettled());
        assertEquals(1, fifth.betsSettled());
        assertEquals(1, fifth.betsVoided());
        assertEquals(0, fifth.session().openBets());

        PaperTradeBet only = paperTradeBetRepository.findAll().get(0);
        assertEquals(PaperTradeBet.STATUS_VOIDED, only.getStatus());
        assertTrue(fifth.session().currentBankroll() >= fifth.session().startingBankroll() - 0.01);
    }

    /**
     * #130 end-to-end: a bet whose live feed dies at a DECISIVE state ("2-0"
     * sets) is settled WON from confidence rather than voided. This is the
     * common Session-65 failure mode — Hard Rock drops the event before the
     * terminal "3-X" and tt-series hasn't posted the block result yet. Per
     * the chosen policy we call the decisive last-state instead of refunding.
     */
    @Test
    void confidenceSettlesDecisiveLastStateInsteadOfVoiding() {
        Player alpha = playerRepository.save(new Player("Conf", "Winner"));
        Player beta = playerRepository.save(new Player("Conf", "Loser"));
        String startIso = isoDateTimeMinutesFromNow(90);

        LiveOddsRecommendationDto prematch = recommendationWithIdentity(
                alpha, beta, startIso, false, null, "UPCOMING",
                "evt-conf-1", "sr:match:conf-1",
                "GQL_MARKET", "HARD_ROCK_GQL:FLORIDA_ONLINE|event=evt-conf-1",
                "Prematch placement");
        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch));
        when(oddsValueEngineService.liveScoreSnapshots(anyInt(), eq(true))).thenReturn(List.of());
        assertEquals(1, paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30).betsPlaced());

        // Live observation: alpha leads 2-0 in sets (decisive, not yet final).
        LiveOddsRecommendationDto live2to0 = recommendationWithIdentity(
                alpha, beta, startIso, true, "2-0", "LIVE_LATE",
                "evt-conf-1", "sr:match:conf-1",
                "GQL_SCOREBOARD", "HARD_ROCK_GQL_SCORE:FLORIDA_ONLINE|event=evt-conf-1",
                "Decisive 2-0 set lead");
        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(live2to0));
        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        PaperTradeBet bet = paperTradeBetRepository.findAll().get(0);
        assertEquals("2-0", bet.getLastObservedScore());

        // Feed dies; match goes stale (presumed finished). Backdate start past
        // the void threshold and last-observation past the confidence stale gate.
        bet.setStartTimeIso(LocalDateTime.now().minusMinutes(200).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        bet.setLastObservedAt(LocalDateTime.now().minusMinutes(40));
        bet.setMissingBoardCount(12);
        paperTradeBetRepository.save(bet);

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of());
        PaperTradingSyncResultDto sweep = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        PaperTradeBet settled = paperTradeBetRepository.findAll().get(0);
        assertEquals(PaperTradeBet.STATUS_WON, settled.getStatus(),
                "decisive 2-0 last state must settle WON, not void");
        assertEquals(0, sweep.betsVoided(), "must NOT void a decisive state");
        assertTrue(settled.getSettlementReason() != null
                        && settled.getSettlementReason().contains("CONFIDENCE"),
                "settlement reason should record the confidence path");
    }

    /**
     * #130 end-to-end: an AMBIGUOUS live state ("2-2 (9-8)") that has gone
     * dark is HELD (not voided) while under the hard cap, giving the
     * official-results recovery time to settle it W/L. Confirms we no longer
     * void settleable-but-pending bets at the short timeout.
     */
    @Test
    void ambiguousLiveStateHeldNotVoidedUnderHardCap() {
        Player alpha = playerRepository.save(new Player("Amb", "Alpha"));
        Player beta = playerRepository.save(new Player("Amb", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(90);

        LiveOddsRecommendationDto prematch = recommendationWithIdentity(
                alpha, beta, startIso, false, null, "UPCOMING",
                "evt-amb-1", "sr:match:amb-1",
                "GQL_MARKET", "HARD_ROCK_GQL:FLORIDA_ONLINE|event=evt-amb-1",
                "Prematch placement");
        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch));
        when(oddsValueEngineService.liveScoreSnapshots(anyInt(), eq(true))).thenReturn(List.of());
        assertEquals(1, paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30).betsPlaced());

        LiveOddsRecommendationDto liveTied = recommendationWithIdentity(
                alpha, beta, startIso, true, "2-2 (9-8)", "LIVE_LATE",
                "evt-amb-1", "sr:match:amb-1",
                "GQL_SCOREBOARD", "HARD_ROCK_GQL_SCORE:FLORIDA_ONLINE|event=evt-amb-1",
                "Ambiguous game-5 deuce");
        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(liveTied));
        paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        PaperTradeBet bet = paperTradeBetRepository.findAll().get(0);
        assertEquals("2-2 (9-8)", bet.getLastObservedScore());

        // Dark + past normal void threshold (200 min) but UNDER the 6h hard cap.
        bet.setStartTimeIso(LocalDateTime.now().minusMinutes(200).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        bet.setLastObservedAt(LocalDateTime.now().minusMinutes(40));
        bet.setMissingBoardCount(12);
        paperTradeBetRepository.save(bet);

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of());
        PaperTradingSyncResultDto sweep = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        assertEquals(0, sweep.betsVoided(), "ambiguous bet must HOLD for official results, not void at 200 min");
        assertEquals(1, sweep.session().openBets(), "bet stays open awaiting official result");
        assertEquals(PaperTradeBet.STATUS_OPEN, paperTradeBetRepository.findAll().get(0).getStatus());
    }

    @Test
    void resetArchivesBetsAndPreservesLearningSamplesForFutureAdaptiveUse() {
        Player alpha = playerRepository.save(new Player("Learn", "Alpha"));
        Player beta = playerRepository.save(new Player("Learn", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(90);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Learn Alpha vs Learn Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.95,
                1.95,
                -105,
                -105,
                0.50,
                0.50,
                0.63,
                0.37,
                0.13,
                -0.13,
                -170,
                170,
                alpha.getName(),
                0.13,
                -170,
                0.56,
                0.69,
                true,
                "A",
                "Learning persistence placement",
                "Head-to-Head (Decayed)",
                0.24,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch));
        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.betsPlaced());
        backdateAllOpenBetStartTimes(180);

        Match completed = new Match();
        completed.setExternalId("learn-persist-1");
        completed.setDate(LocalDate.now());
        completed.setPlayer1(alpha);
        completed.setPlayer2(beta);
        MatchResultParser.applyToMatch(completed, "3:1");
        matchRepository.save(completed);

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of());
        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, second.betsSettled());
        assertEquals(1, paperTradeLearningSampleRepository.count());

        var reset = paperTradingService.resetSession(1000.0, "Fresh Session", true);
        assertEquals(0, reset.totalBets());
        assertEquals(0, reset.openBets());
        assertEquals(1, paperTradeBetRepository.count());
        assertEquals(1, paperTradeLearningSampleRepository.count());
        assertTrue(reset.adaptiveMetrics().sampleSize() >= 1);
    }

    @Test
    void settlementMatchesScoreboardRowsWithInitialsAfterOddsClose() {
        Player alpha = playerRepository.save(new Player("Marek", "Kowol"));
        Player beta = playerRepository.save(new Player("Adam", "Linek"));
        String startIso = isoDateTimeMinutesFromNow(60);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Marek Kowol vs Adam Linek",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.95,
                1.95,
                -105,
                -105,
                0.50,
                0.50,
                0.62,
                0.38,
                0.12,
                -0.12,
                -163,
                163,
                alpha.getName(),
                0.12,
                -163,
                0.54,
                0.68,
                true,
                "A",
                "Initials scoreboard mapping test",
                "Head-to-Head Decayed",
                0.23,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        LiveScoreSnapshotDto scoreboardRow = new LiveScoreSnapshotDto(
                "HARD_ROCK_GQL_SCORE:FLORIDA_ONLINE",
                "GQL_SCOREBOARD",
                0.90,
                0L,
                "M. Kowol vs Adam Linek",
                "TT Elite Series",
                true,
                startIso,
                "3-1",
                "LIVE_LATE",
                null,
                true,
                false,
                false,
                null,
                null,
                null,
                null,
                "M. Kowol",
                null,
                "Adam Linek",
                matchupKey(alpha, beta, startIso)
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch), List.of());
        when(oddsValueEngineService.liveScoreSnapshots(anyInt(), eq(true)))
                .thenReturn(List.of(), List.of(scoreboardRow));

        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.betsPlaced());
        backdateAllOpenBetStartTimes(180);

        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, second.betsSettled());
        assertEquals(0, second.session().openBets());
        PaperTradeBet settled = paperTradeBetRepository.findAll().get(0);
        assertEquals(PaperTradeBet.STATUS_WON, settled.getStatus());
        assertEquals("SETTLED_FROM_DECISIVE_LIVE_SCORE", settled.getSettlementReason());
        assertEquals("DECISIVE_LIVE_SCORE", settled.getSettlementSource());
    }

    @Test
    void scoredOpenBetDoesNotVoidAtDefaultTimeoutWhileLiveContextExists() {
        Player alpha = playerRepository.save(new Player("Timeout", "Alpha"));
        Player beta = playerRepository.save(new Player("Timeout", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(30);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Timeout Alpha vs Timeout Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.96,
                1.92,
                -104,
                -108,
                0.50,
                0.50,
                0.61,
                0.39,
                0.11,
                -0.11,
                -156,
                156,
                alpha.getName(),
                0.11,
                -156,
                0.53,
                0.67,
                true,
                "A",
                "Timeout guard for scored live context",
                "Recent Form Delta",
                0.21,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch), List.of());
        when(oddsValueEngineService.liveScoreSnapshots(anyInt(), eq(true)))
                .thenReturn(List.of(), List.of());

        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.betsPlaced());

        PaperTradeBet open = paperTradeBetRepository.findAll().stream()
                .filter(b -> PaperTradeBet.STATUS_OPEN.equals(b.getStatus()))
                .findFirst()
                .orElseThrow();
        // #123 — Backdate to 170 min (was 200 min). With the new phase-aware
        // void timeout (LIVE_LATE → scoreGrace 90, was 240), the effective
        // threshold for a LIVE_LATE bet with score context is max(180, 60+90)
        // = 180 min. The original 200-min backdate would now correctly trigger
        // a void — exactly the behaviour #123 was built to enable. 170 min
        // keeps the bet inside the live-context grace window so the assertion
        // (live context protects from default timeout) still holds.
        open.setStartTimeIso(LocalDateTime.now().minusMinutes(170).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        open.setLastObservedScore("2-2 (3-3)");
        open.setLastObservedPhase("LIVE_LATE");
        open.setLastObservedAt(LocalDateTime.now().minusMinutes(20));
        open.setMissingBoardCount(10);
        paperTradeBetRepository.save(open);

        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(0, second.betsSettled());
        assertEquals(0, second.betsVoided());
        assertEquals(1, second.session().openBets());
        PaperTradeBet tracked = paperTradeBetRepository.findAll().get(0);
        assertEquals(PaperTradeBet.STATUS_OPEN, tracked.getStatus());
    }

    @Test
    void recentTrackedAfterCloseObservationKeepsBetOpenWhenBoardAndTargetedFeedsMissCurrentSync() {
        Player alpha = playerRepository.save(new Player("Tracked", "Alpha"));
        Player beta = playerRepository.save(new Player("Tracked", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(45);
        String externalEventId = "evt-tracked-grace-1";

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "HARD_ROCK_GQL:FLORIDA_ONLINE|event=" + externalEventId,
                "CONSERVATIVE",
                "ENSEMBLE",
                "Tracked Alpha vs Tracked Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.94,
                1.94,
                -106,
                -106,
                0.50,
                0.50,
                0.61,
                0.39,
                0.11,
                -0.11,
                -156,
                156,
                alpha.getName(),
                0.11,
                -156,
                0.54,
                0.69,
                true,
                "A",
                "Tracked-after-close grace setup",
                "Head-to-Head Decayed",
                0.22,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        LiveScoreSnapshotDto targetedSnapshot = new LiveScoreSnapshotDto(
                "HARD_ROCK_GQL_SCORE:FLORIDA_ONLINE|event=" + externalEventId,
                "GQL_TRACKED_EVENT",
                0.97,
                0L,
                "Tracked Alpha vs Tracked Beta",
                "TTL Elite Series",
                true,
                startIso,
                "2-2 (5-5)",
                "LIVE_LATE",
                externalEventId,
                false,
                false,
                false,
                null,
                null,
                null,
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                matchupKey(alpha, beta, startIso)
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch), List.of(), List.of());
        when(oddsValueEngineService.liveScoreSnapshots(anyInt(), eq(true)))
                .thenReturn(List.of(), List.of(), List.of());
        when(oddsValueEngineService.liveScoreSnapshotsForEventIds(
                argThat(ids -> ids != null && ids.contains(externalEventId)),
                anyInt(),
                eq(true)
        )).thenReturn(List.of(targetedSnapshot), List.of());

        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.betsPlaced());
        backdateAllOpenBetStartTimes(400);

        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(0, second.betsSettled());
        assertEquals(1, second.session().openBets());

        PaperTradeBet open = paperTradeBetRepository.findAll().stream()
                .filter(b -> PaperTradeBet.STATUS_OPEN.equals(b.getStatus()))
                .findFirst()
                .orElseThrow();
        assertTrue(open.isTrackedAfterClose());
        assertEquals("SCORE_FEED", open.getLastScoreSource());
        open.setMissingBoardCount(10);
        paperTradeBetRepository.save(open);

        PaperTradingSyncResultDto third = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(0, third.betsSettled());
        assertEquals(0, third.betsVoided());
        assertEquals(1, third.session().openBets());

        PaperTradeBet stillOpen = paperTradeBetRepository.findAll().stream()
                .filter(b -> PaperTradeBet.STATUS_OPEN.equals(b.getStatus()))
                .findFirst()
                .orElseThrow();
        assertEquals(PaperTradeBet.STATUS_OPEN, stillOpen.getStatus());
        assertTrue(stillOpen.isTrackedAfterClose());
        assertEquals("2-2 (5-5)", stillOpen.getLastObservedScore());
        assertEquals("LIVE_LATE", stillOpen.getLastObservedPhase());
        assertEquals(0, stillOpen.getMissingBoardCount());
    }

    @Test
    void staleTrackedAfterCloseObservationSettlesFromDatabaseBeforeVoiding() {
        Player alpha = playerRepository.save(new Player("Fallback", "Alpha"));
        Player beta = playerRepository.save(new Player("Fallback", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(45);
        String externalEventId = "evt-tracked-db-1";

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "HARD_ROCK_GQL:FLORIDA_ONLINE|event=" + externalEventId,
                "CONSERVATIVE",
                "ENSEMBLE",
                "Fallback Alpha vs Fallback Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.96,
                1.92,
                -104,
                -108,
                0.51,
                0.49,
                0.62,
                0.38,
                0.11,
                -0.11,
                -163,
                163,
                alpha.getName(),
                0.11,
                -163,
                0.55,
                0.70,
                true,
                "A",
                "Tracked-after-close database fallback setup",
                "Head-to-Head Decayed",
                0.22,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        LiveScoreSnapshotDto targetedSnapshot = new LiveScoreSnapshotDto(
                "HARD_ROCK_GQL_SCORE:FLORIDA_ONLINE|event=" + externalEventId,
                "GQL_TRACKED_EVENT",
                0.97,
                0L,
                "Fallback Alpha vs Fallback Beta",
                "TTL Elite Series",
                true,
                startIso,
                "2-2 (8-8)",
                "LIVE_LATE",
                externalEventId,
                false,
                false,
                false,
                null,
                null,
                null,
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                matchupKey(alpha, beta, startIso)
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch), List.of(), List.of());
        when(oddsValueEngineService.liveScoreSnapshots(anyInt(), eq(true)))
                .thenReturn(List.of(), List.of(), List.of());
        when(oddsValueEngineService.liveScoreSnapshotsForEventIds(
                argThat(ids -> ids != null && ids.contains(externalEventId)),
                anyInt(),
                eq(true)
        )).thenReturn(List.of(targetedSnapshot), List.of());

        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.betsPlaced());
        backdateAllOpenBetStartTimes(400);

        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(0, second.betsSettled());
        assertEquals(1, second.session().openBets());

        PaperTradeBet open = paperTradeBetRepository.findAll().stream()
                .filter(b -> PaperTradeBet.STATUS_OPEN.equals(b.getStatus()))
                .findFirst()
                .orElseThrow();
        assertTrue(open.isTrackedAfterClose());

        List<TrackedMatchObservation> observations = trackedMatchObservationRepository
                .findByBetIdOrderByObservedAtAsc(open.getId());
        assertEquals(2, observations.size());
        observations.get(0).setObservedAt(LocalDateTime.now().minusMinutes(120));
        observations.get(1).setObservedAt(LocalDateTime.now().minusMinutes(90));
        trackedMatchObservationRepository.saveAll(observations);

        Match completed = new Match();
        completed.setExternalId("tracked-db-fallback-1");
        completed.setDate(LocalDate.now());
        completed.setPlayer1(alpha);
        completed.setPlayer2(beta);
        MatchResultParser.applyToMatch(completed, "3:2");
        matchRepository.save(completed);

        PaperTradingSyncResultDto third = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, third.betsSettled());
        assertEquals(0, third.betsVoided());
        assertEquals(0, third.session().openBets());

        PaperTradeBet settled = paperTradeBetRepository.findAll().get(0);
        assertEquals(PaperTradeBet.STATUS_WON, settled.getStatus());
        assertEquals("SETTLED_FROM_OFFICIAL_RESULT_TRACKED_AFTER_CLOSE", settled.getSettlementReason());
        assertEquals("OFFICIAL_RESULT", settled.getSettlementSource());
        assertNotNull(settled.getResultMatchId());
    }

    @Test
    void targetedCompletedSnapshotSettlesBeforeDatabaseFallback() {
        Player alpha = playerRepository.save(new Player("Targeted", "Alpha"));
        Player beta = playerRepository.save(new Player("Targeted", "Beta"));
        String placementStartIso = isoDateTimeMinutesFromNow(45);
        String completedStartIso = LocalDateTime.now().minusMinutes(200).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String externalEventId = "evt-targeted-complete-1";

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "HARD_ROCK_GQL:FLORIDA_ONLINE|event=" + externalEventId,
                "CONSERVATIVE",
                "ENSEMBLE",
                "Targeted Alpha vs Targeted Beta",
                "TTL Elite Series",
                false,
                placementStartIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.95,
                1.95,
                -105,
                -105,
                0.50,
                0.50,
                0.62,
                0.38,
                0.12,
                -0.12,
                -163,
                163,
                alpha.getName(),
                0.12,
                -163,
                0.55,
                0.70,
                true,
                "A",
                "Targeted completion setup",
                "Head-to-Head Decayed",
                0.22,
                matchupKey(alpha, beta, placementStartIso),
                dedupeKey(alpha, beta, placementStartIso, alpha.getName())
        );

        LiveScoreSnapshotDto targetedCompletedSnapshot = new LiveScoreSnapshotDto(
                "HARD_ROCK_GQL_SCORE:FLORIDA_ONLINE|event=" + externalEventId,
                "GQL_TRACKED_EVENT",
                0.98,
                0L,
                "Targeted Alpha vs Targeted Beta",
                "TTL Elite Series",
                true,
                completedStartIso,
                "3-1 (0-0)",
                "LIVE_LATE",
                externalEventId,
                false,
                false,
                true,
                "BETRADAR_UF",
                "sr:match:70525978",
                "11-7, 11-8, 8-11, 11-6",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                matchupKey(alpha, beta, placementStartIso)
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch), List.of());
        when(oddsValueEngineService.liveScoreSnapshots(anyInt(), eq(true)))
                .thenReturn(List.of(), List.of());
        when(oddsValueEngineService.liveScoreSnapshotsForEventIds(
                argThat(ids -> ids != null && ids.contains(externalEventId)),
                anyInt(),
                eq(true)
        )).thenReturn(List.of(targetedCompletedSnapshot));

        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.betsPlaced());
        backdateAllOpenBetStartTimes(400);

        Match conflictingDatabaseMatch = new Match();
        conflictingDatabaseMatch.setExternalId("targeted-conflict-db-1");
        conflictingDatabaseMatch.setDate(LocalDate.now());
        conflictingDatabaseMatch.setPlayer1(alpha);
        conflictingDatabaseMatch.setPlayer2(beta);
        MatchResultParser.applyToMatch(conflictingDatabaseMatch, "1:3");
        matchRepository.save(conflictingDatabaseMatch);

        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, second.betsSettled());
        assertEquals(0, second.betsVoided());
        assertEquals(0, second.session().openBets());

        PaperTradeBet settled = paperTradeBetRepository.findAll().get(0);
        assertEquals(PaperTradeBet.STATUS_WON, settled.getStatus());
        assertEquals("SETTLED_FROM_TARGETED_MATCH_COMPLETED", settled.getSettlementReason());
        assertEquals("DECISIVE_LIVE_SCORE", settled.getSettlementSource());
        assertEquals(externalEventId, settled.getExternalEventId());
        assertEquals("3-1 (0-0)", settled.getLastObservedScore());
        assertTrue(settled.isTrackedAfterClose());
        assertEquals("SCORE_FEED", settled.getLastScoreSource());
    }

    @Test
    void timelinePersistsTargetedCompletionEvidence() {
        Player alpha = playerRepository.save(new Player("Timeline", "Alpha"));
        Player beta = playerRepository.save(new Player("Timeline", "Beta"));
        String placementStartIso = isoDateTimeMinutesFromNow(35);
        String completedStartIso = LocalDateTime.now().minusMinutes(120).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String externalEventId = "evt-timeline-evidence-1";
        String eventKey = matchupKey(alpha, beta, placementStartIso);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "HARD_ROCK_GQL:FLORIDA_ONLINE|event=" + externalEventId,
                "CONSERVATIVE",
                "ENSEMBLE",
                "Timeline Alpha vs Timeline Beta",
                "TTL Elite Series",
                false,
                placementStartIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.91,
                1.98,
                -110,
                -102,
                0.52,
                0.48,
                0.61,
                0.39,
                0.09,
                -0.09,
                -156,
                156,
                alpha.getName(),
                0.09,
                -156,
                0.53,
                0.67,
                true,
                "B",
                "Timeline evidence setup",
                "Recent Form Delta",
                0.18,
                eventKey,
                dedupeKey(alpha, beta, placementStartIso, alpha.getName())
        );

        LiveScoreSnapshotDto targetedCompletedSnapshot = new LiveScoreSnapshotDto(
                "HARD_ROCK_GQL_SCORE:FLORIDA_ONLINE|event=" + externalEventId,
                "GQL_TRACKED_EVENT",
                0.99,
                0L,
                "Timeline Alpha vs Timeline Beta",
                "TTL Elite Series",
                true,
                completedStartIso,
                "3-0 (0-0)",
                "LIVE_LATE",
                externalEventId,
                false,
                false,
                true,
                "BETRADAR_UF",
                "sr:match:70999999",
                "11-6, 11-8, 11-5",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                eventKey
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch), List.of());
        when(oddsValueEngineService.liveScoreSnapshots(anyInt(), eq(true)))
                .thenReturn(List.of(), List.of());
        when(oddsValueEngineService.liveScoreSnapshotsForEventIds(
                argThat(ids -> ids != null && ids.contains(externalEventId)),
                anyInt(),
                eq(true)
        )).thenReturn(List.of(targetedCompletedSnapshot));

        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.betsPlaced());
        backdateAllOpenBetStartTimes(300);

        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, second.betsSettled());

        List<TrackedMatchObservationDto> timeline = paperTradingService.getMatchTimeline(eventKey);
        assertFalse(timeline.isEmpty());
        TrackedMatchObservationDto latest = timeline.get(timeline.size() - 1);
        assertEquals(externalEventId, latest.externalEventId());
        assertEquals("SCORE_FEED", latest.sourceKind());
        assertTrue(latest.trackedAfterClose());
        assertFalse(latest.displayed());
        assertFalse(latest.resulted());
        assertTrue(latest.matchCompleted());
        assertEquals("BETRADAR_UF", latest.sourceFeedCode());
        assertEquals("sr:match:70999999", latest.sourceFeedEventId());
        assertEquals("11-6, 11-8, 11-5", latest.scoreDetail());

        PaperTradeBet settled = paperTradeBetRepository.findAll().get(0);
        assertTrue(settled.isLastMatchCompleted());
        assertFalse(settled.isLastObservationDisplayed());
        assertFalse(settled.isLastObservationResulted());
        assertEquals("BETRADAR_UF", settled.getLastSourceFeedCode());
        assertEquals("sr:match:70999999", settled.getLastSourceFeedEventId());
        assertEquals("11-6, 11-8, 11-5", settled.getLastScoreDetail());
        assertNull(settled.getResultMatchId());
    }

    @Test
    void integrityCountsTargetedCompletionSettlementsSeparately() {
        Player alpha = playerRepository.save(new Player("Integrity", "Alpha"));
        Player beta = playerRepository.save(new Player("Integrity", "Beta"));
        String placementStartIso = isoDateTimeMinutesFromNow(30);
        String completedStartIso = LocalDateTime.now().minusMinutes(100).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String externalEventId = "evt-integrity-targeted-1";

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "HARD_ROCK_GQL:FLORIDA_ONLINE|event=" + externalEventId,
                "CONSERVATIVE",
                "ENSEMBLE",
                "Integrity Alpha vs Integrity Beta",
                "TTL Elite Series",
                false,
                placementStartIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.96,
                1.92,
                -104,
                -108,
                0.51,
                0.49,
                0.63,
                0.37,
                0.12,
                -0.12,
                -170,
                170,
                alpha.getName(),
                0.12,
                -170,
                0.56,
                0.71,
                true,
                "A",
                "Integrity targeted setup",
                "Head-to-Head Decayed",
                0.24,
                matchupKey(alpha, beta, placementStartIso),
                dedupeKey(alpha, beta, placementStartIso, alpha.getName())
        );

        LiveScoreSnapshotDto targetedCompletedSnapshot = new LiveScoreSnapshotDto(
                "HARD_ROCK_GQL_SCORE:FLORIDA_ONLINE|event=" + externalEventId,
                "GQL_TRACKED_EVENT",
                0.99,
                0L,
                "Integrity Alpha vs Integrity Beta",
                "TTL Elite Series",
                true,
                completedStartIso,
                "3-0 (0-0)",
                "LIVE_LATE",
                externalEventId,
                false,
                false,
                true,
                "BETRADAR_UF",
                "sr:match:70000123",
                "11-9, 11-7, 11-4",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                matchupKey(alpha, beta, placementStartIso)
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch), List.of());
        when(oddsValueEngineService.liveScoreSnapshots(anyInt(), eq(true)))
                .thenReturn(List.of(), List.of());
        when(oddsValueEngineService.liveScoreSnapshotsForEventIds(
                argThat(ids -> ids != null && ids.contains(externalEventId)),
                anyInt(),
                eq(true)
        )).thenReturn(List.of(targetedCompletedSnapshot));

        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.betsPlaced());
        backdateAllOpenBetStartTimes(300);

        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, second.betsSettled());

        LiveStudioIntegrityDto integrity = paperTradingService.getLiveStudioIntegrity();
        assertEquals(1, integrity.targetedCompletionSettlements());
        assertEquals(0, integrity.scoreBackedSettlements());
        assertEquals(1, integrity.trackedAfterCloseObservations());
        assertEquals(1, integrity.scoreFeedObservations());
    }

    @Test
    void visibleUpcomingScoreFeedObservationDoesNotMarkBetAsTrackedAfterClose() {
        Player alpha = playerRepository.save(new Player("Visible", "Alpha"));
        Player beta = playerRepository.save(new Player("Visible", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(35);
        String externalEventId = "evt-visible-score-feed-1";
        String eventKey = matchupKey(alpha, beta, startIso);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "HARD_ROCK_GQL:FLORIDA_ONLINE|event=" + externalEventId,
                "CONSERVATIVE",
                "ENSEMBLE",
                "Visible Alpha vs Visible Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.85,
                2.0,
                -118,
                100,
                0.54,
                0.46,
                0.61,
                0.39,
                0.09,
                -0.09,
                -156,
                156,
                alpha.getName(),
                0.09,
                -156,
                0.53,
                0.72,
                true,
                "A",
                "Visible score-feed smoke case",
                "Head-to-Head Decayed",
                0.18,
                eventKey,
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        LiveScoreSnapshotDto visibleTrackedSnapshot = new LiveScoreSnapshotDto(
                "HARD_ROCK_GQL_SCORE:FLORIDA_ONLINE|event=" + externalEventId,
                "GQL_TRACKED_EVENT",
                0.90,
                0L,
                "Visible Alpha vs Visible Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                externalEventId,
                true,
                false,
                false,
                "BETRADAR_UF",
                "sr:match:70000999",
                null,
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                eventKey
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch));
        when(oddsValueEngineService.liveScoreSnapshots(anyInt(), eq(true)))
                .thenReturn(List.of());
        when(oddsValueEngineService.liveScoreSnapshotsForEventIds(
                argThat(ids -> ids != null && ids.contains(externalEventId)),
                anyInt(),
                eq(true)
        )).thenReturn(List.of(visibleTrackedSnapshot));

        PaperTradingSyncResultDto result = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, result.betsPlaced());

        PaperTradeBet openBet = paperTradeBetRepository.findAll().get(0);
        assertEquals("SCORE_FEED", openBet.getLastScoreSource());
        assertTrue(openBet.isLastObservationDisplayed());
        assertFalse(openBet.isTrackedAfterClose());

        PaperTradeBetDto dto = paperTradingService.getLiveStudioOpenBets().get(0);
        assertEquals("OPEN_PENDING_SCORE", dto.trackingState());

        LiveStudioIntegrityDto integrity = paperTradingService.getLiveStudioIntegrity();
        assertEquals(1, integrity.scoreFeedObservations());
        assertEquals(0, integrity.trackedAfterCloseObservations());
    }

    @Test
    void targetedCompletionWithUnchangedScoreStillPersistsObservationAndSettles() {
        Player alpha = playerRepository.save(new Player("Completion", "Alpha"));
        Player beta = playerRepository.save(new Player("Completion", "Beta"));
        String placementStartIso = isoDateTimeMinutesFromNow(40);
        String eventKey = matchupKey(alpha, beta, placementStartIso);
        String externalEventId = "evt-targeted-same-score-1";
        String trackedStartIso = LocalDateTime.now().minusMinutes(90).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String completedStartIso = LocalDateTime.now().minusMinutes(80).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "HARD_ROCK_GQL:FLORIDA_ONLINE|event=" + externalEventId,
                "CONSERVATIVE",
                "ENSEMBLE",
                "Completion Alpha vs Completion Beta",
                "TTL Elite Series",
                false,
                placementStartIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.92,
                1.98,
                -109,
                -101,
                0.51,
                0.49,
                0.61,
                0.39,
                0.10,
                -0.10,
                -156,
                156,
                alpha.getName(),
                0.10,
                -156,
                0.55,
                0.68,
                true,
                "A",
                "Targeted completion unchanged-score setup",
                "Head-to-Head Decayed",
                0.22,
                eventKey,
                dedupeKey(alpha, beta, placementStartIso, alpha.getName())
        );

        LiveScoreSnapshotDto trackedSnapshot = new LiveScoreSnapshotDto(
                "HARD_ROCK_GQL_SCORE:FLORIDA_ONLINE|event=" + externalEventId,
                "GQL_TRACKED_EVENT",
                0.96,
                0L,
                "Completion Alpha vs Completion Beta",
                "TTL Elite Series",
                true,
                trackedStartIso,
                "2-2 (10-7)",
                "LIVE_LATE",
                externalEventId,
                false,
                false,
                false,
                "BETRADAR_UF",
                "sr:match:72000101",
                "11-9, 8-11, 11-7, 7-11, 10-7",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                eventKey
        );

        LiveScoreSnapshotDto completedSnapshot = new LiveScoreSnapshotDto(
                "HARD_ROCK_GQL_SCORE:FLORIDA_ONLINE|event=" + externalEventId,
                "GQL_TRACKED_EVENT",
                0.99,
                0L,
                "Completion Alpha vs Completion Beta",
                "TTL Elite Series",
                true,
                completedStartIso,
                "2-2 (10-7)",
                "LIVE_LATE",
                externalEventId,
                false,
                false,
                true,
                "BETRADAR_UF",
                "sr:match:72000101",
                "11-9, 8-11, 11-7, 7-11, 10-7",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                eventKey
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch), List.of(), List.of());
        when(oddsValueEngineService.liveScoreSnapshots(anyInt(), eq(true)))
                .thenReturn(List.of(), List.of(), List.of());
        when(oddsValueEngineService.liveScoreSnapshotsForEventIds(
                argThat(ids -> ids != null && ids.contains(externalEventId)),
                anyInt(),
                eq(true)
        )).thenReturn(List.of(trackedSnapshot), List.of(completedSnapshot));

        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.betsPlaced());
        backdateAllOpenBetStartTimes(300);

        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(0, second.betsSettled());
        assertEquals(1, second.session().openBets());

        PaperTradingSyncResultDto third = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, third.betsSettled());
        assertEquals(0, third.session().openBets());

        PaperTradeBet settled = paperTradeBetRepository.findAll().get(0);
        assertEquals(PaperTradeBet.STATUS_WON, settled.getStatus());
        assertEquals("SETTLED_FROM_TARGETED_MATCH_COMPLETED", settled.getSettlementReason());
        assertEquals("DECISIVE_LIVE_SCORE", settled.getSettlementSource());
        assertTrue(settled.isLastMatchCompleted());
        assertEquals("2-2 (10-7)", settled.getLastObservedScore());

        List<TrackedMatchObservationDto> timeline = paperTradingService.getMatchTimeline(eventKey);
        assertEquals(3, timeline.size());
        List<TrackedMatchObservationDto> scoreFeedTimeline = new ArrayList<>(timeline.stream()
                .filter(observation -> "SCORE_FEED".equals(observation.sourceKind()))
                .toList());
        assertTrue(scoreFeedTimeline.size() >= 2);
        TrackedMatchObservationDto penultimateScore = scoreFeedTimeline.get(scoreFeedTimeline.size() - 2);
        TrackedMatchObservationDto latestScore = scoreFeedTimeline.get(scoreFeedTimeline.size() - 1);
        assertEquals("2-2 (10-7)", penultimateScore.liveScore());
        assertFalse(penultimateScore.matchCompleted());
        assertEquals("2-2 (10-7)", latestScore.liveScore());
        assertTrue(latestScore.matchCompleted());
    }

    @Test
    void trackedScoreReappearanceClearsMissingBoardCountAndKeepsBetOpen() {
        Player alpha = playerRepository.save(new Player("Reappear", "Alpha"));
        Player beta = playerRepository.save(new Player("Reappear", "Beta"));
        String placementStartIso = isoDateTimeMinutesFromNow(50);
        String eventKey = matchupKey(alpha, beta, placementStartIso);
        String externalEventId = "evt-reappear-score-1";
        String trackedStartIso = LocalDateTime.now().minusMinutes(40).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "HARD_ROCK_GQL:FLORIDA_ONLINE|event=" + externalEventId,
                "CONSERVATIVE",
                "ENSEMBLE",
                "Reappear Alpha vs Reappear Beta",
                "TTL Elite Series",
                false,
                placementStartIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.91,
                1.99,
                -110,
                -100,
                0.51,
                0.49,
                0.60,
                0.40,
                0.09,
                -0.09,
                -150,
                150,
                alpha.getName(),
                0.09,
                -150,
                0.54,
                0.67,
                true,
                "B",
                "Tracked reappearance setup",
                "Recent Form Delta",
                0.19,
                eventKey,
                dedupeKey(alpha, beta, placementStartIso, alpha.getName())
        );

        LiveScoreSnapshotDto reappearedTrackedSnapshot = new LiveScoreSnapshotDto(
                "HARD_ROCK_GQL_SCORE:FLORIDA_ONLINE|event=" + externalEventId,
                "GQL_TRACKED_EVENT",
                0.92,
                0L,
                "Reappear Alpha vs Reappear Beta",
                "TTL Elite Series",
                true,
                trackedStartIso,
                "1-1 (0-0)",
                "LIVE_MID",
                externalEventId,
                false,
                false,
                false,
                "BETRADAR_UF",
                "sr:match:72000102",
                "11-8, 8-11",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                eventKey
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch), List.of(), List.of());
        when(oddsValueEngineService.liveScoreSnapshots(anyInt(), eq(true)))
                .thenReturn(List.of(), List.of(), List.of());
        when(oddsValueEngineService.liveScoreSnapshotsForEventIds(
                argThat(ids -> ids != null && ids.contains(externalEventId)),
                anyInt(),
                eq(true)
        )).thenReturn(List.of(), List.of(reappearedTrackedSnapshot));

        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.betsPlaced());

        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(0, second.betsSettled());
        PaperTradeBet afterGap = paperTradeBetRepository.findAll().get(0);
        afterGap.setMissingBoardCount(2);
        paperTradeBetRepository.save(afterGap);

        PaperTradingSyncResultDto third = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(0, third.betsSettled());
        assertEquals(1, third.session().openBets());

        PaperTradeBet reopened = paperTradeBetRepository.findAll().get(0);
        assertEquals(PaperTradeBet.STATUS_OPEN, reopened.getStatus());
        assertEquals(0, reopened.getMissingBoardCount());
        assertTrue(reopened.isTrackedAfterClose());
        assertEquals("1-1 (0-0)", reopened.getLastObservedScore());
        assertEquals("SCORE_FEED", reopened.getLastScoreSource());

        List<TrackedMatchObservation> observations = trackedMatchObservationRepository
                .findByBetIdOrderByObservedAtAsc(reopened.getId());
        assertEquals(2, observations.size());
        assertEquals("1-1 (0-0)", observations.get(observations.size() - 1).getLiveScore());
        assertTrue(observations.get(observations.size() - 1).isTrackedAfterClose());
    }

    @Test
    void databaseSettlementPrefersFeedIdentityOverPlayerDateHeuristic() {
        Player alpha = playerRepository.save(new Player("FeedId", "Alpha"));
        Player beta = playerRepository.save(new Player("FeedId", "Beta"));
        String placementStartIso = isoDateTimeMinutesFromNow(40);
        String trackedStartIso = LocalDateTime.now().minusMinutes(90).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String externalEventId = "evt-feed-identity-db-1";
        String sourceFeedEventId = "sr:match:71110001";
        String eventKey = matchupKey(alpha, beta, placementStartIso);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "HARD_ROCK_GQL:FLORIDA_ONLINE|event=" + externalEventId,
                "CONSERVATIVE",
                "ENSEMBLE",
                "FeedId Alpha vs FeedId Beta",
                "TTL Elite Series",
                false,
                placementStartIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.92,
                1.98,
                -109,
                -101,
                0.51,
                0.49,
                0.60,
                0.40,
                0.09,
                -0.09,
                -150,
                150,
                alpha.getName(),
                0.09,
                -150,
                0.54,
                0.67,
                true,
                "B",
                "Feed identity setup",
                "Recent Form Delta",
                0.20,
                eventKey,
                dedupeKey(alpha, beta, placementStartIso, alpha.getName())
        );

        LiveScoreSnapshotDto trackedSnapshot = new LiveScoreSnapshotDto(
                "HARD_ROCK_GQL_SCORE:FLORIDA_ONLINE|event=" + externalEventId,
                "GQL_TRACKED_EVENT",
                0.92,
                0L,
                "FeedId Alpha vs FeedId Beta",
                "TTL Elite Series",
                true,
                trackedStartIso,
                "1-1 (0-0)",
                "LIVE_MID",
                externalEventId,
                false,
                false,
                false,
                "BETRADAR_UF",
                sourceFeedEventId,
                "11-9, 8-11",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                eventKey
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch), List.of(), List.of());
        when(oddsValueEngineService.liveScoreSnapshots(anyInt(), eq(true)))
                .thenReturn(List.of(), List.of(), List.of());
        when(oddsValueEngineService.liveScoreSnapshotsForEventIds(
                argThat(ids -> ids != null && ids.contains(externalEventId)),
                anyInt(),
                eq(true)
        )).thenReturn(List.of(trackedSnapshot), List.of());

        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.betsPlaced());
        backdateAllOpenBetStartTimes(400);

        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(0, second.betsSettled());

        PaperTradeBet trackedBet = paperTradeBetRepository.findAll().get(0);
        assertTrue(trackedBet.isTrackedAfterClose());
        assertEquals(sourceFeedEventId, trackedBet.getLastSourceFeedEventId());
        assertEquals("1-1 (0-0)", trackedBet.getLastObservedScore());

        TrackedMatchObservation observation = trackedMatchObservationRepository
                .findTopByBetIdOrderByObservedAtDescIdDesc(trackedBet.getId())
                .orElseThrow();
        observation.setObservedAt(LocalDateTime.now().minusMinutes(400));
        trackedMatchObservationRepository.save(observation);

        trackedBet.setLastObservedAt(LocalDateTime.now().minusMinutes(400));
        paperTradeBetRepository.save(trackedBet);

        Match heuristicCandidate = new Match();
        heuristicCandidate.setExternalId("feed-id-heuristic-candidate");
        heuristicCandidate.setDate(LocalDate.now());
        heuristicCandidate.setPlayer1(alpha);
        heuristicCandidate.setPlayer2(beta);
        MatchResultParser.applyToMatch(heuristicCandidate, "1:3");
        matchRepository.save(heuristicCandidate);

        Match feedIdentityCandidate = new Match();
        feedIdentityCandidate.setExternalId("feed-id-direct-candidate");
        feedIdentityCandidate.setSourceFeedCode("BETRADAR_UF");
        feedIdentityCandidate.setSourceFeedEventId(sourceFeedEventId);
        feedIdentityCandidate.setDate(LocalDate.now());
        feedIdentityCandidate.setPlayer1(alpha);
        feedIdentityCandidate.setPlayer2(beta);
        MatchResultParser.applyToMatch(feedIdentityCandidate, "3:1");
        matchRepository.save(feedIdentityCandidate);

        List<Match> feedIdentityMatches = matchRepository.findMatchesByFeedEventIdentity(
                sourceFeedEventId,
                PageRequest.of(0, 10)
        );
        assertEquals(1, feedIdentityMatches.size());
        assertEquals(feedIdentityCandidate.getId(), feedIdentityMatches.get(0).getId());

        PaperTradingSyncResultDto third = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, third.betsSettled());
        assertEquals(0, third.session().openBets());

        PaperTradeBet settled = paperTradeBetRepository.findAll().get(0);
        assertEquals(PaperTradeBet.STATUS_WON, settled.getStatus());
        assertEquals("SETTLED_FROM_OFFICIAL_RESULT_FEED_IDENTITY_TRACKED_AFTER_CLOSE", settled.getSettlementReason());
        assertEquals("OFFICIAL_RESULT", settled.getSettlementSource());
        assertEquals(feedIdentityCandidate.getId(), settled.getResultMatchId());
    }

    @Test
    void officialResultRefreshSettlesTrackedAfterCloseBetBeforeGenericDatabaseFallback() throws Exception {
        Player alpha = playerRepository.save(new Player("Official", "Alpha"));
        Player beta = playerRepository.save(new Player("Official", "Beta"));
        String placementStartIso = isoDateTimeMinutesFromNow(35);
        String trackedStartIso = LocalDateTime.now().minusMinutes(120).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String externalEventId = "evt-official-result-1";
        String eventKey = matchupKey(alpha, beta, placementStartIso);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "HARD_ROCK_GQL:FLORIDA_ONLINE|event=" + externalEventId,
                "CONSERVATIVE",
                "ENSEMBLE",
                "Official Alpha vs Official Beta",
                "TTL Elite Series",
                false,
                placementStartIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.94,
                1.96,
                -106,
                -104,
                0.50,
                0.50,
                0.59,
                0.41,
                0.09,
                -0.09,
                -150,
                150,
                alpha.getName(),
                0.09,
                -150,
                0.54,
                0.67,
                true,
                "B",
                "Official result refresh setup",
                "Recent Form Delta",
                0.21,
                eventKey,
                dedupeKey(alpha, beta, placementStartIso, alpha.getName())
        );

        LiveScoreSnapshotDto trackedSnapshot = new LiveScoreSnapshotDto(
                "HARD_ROCK_GQL_SCORE:FLORIDA_ONLINE|event=" + externalEventId,
                "GQL_TRACKED_EVENT",
                0.92,
                0L,
                "Official Alpha vs Official Beta",
                "TTL Elite Series",
                true,
                trackedStartIso,
                "2-2 (9-9)",
                "LIVE_LATE",
                externalEventId,
                false,
                false,
                false,
                "BETRADAR_UF",
                null,
                "11-8, 9-11, 11-7, 8-11",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                eventKey
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch), List.of(), List.of());
        when(oddsValueEngineService.liveScoreSnapshots(anyInt(), eq(true)))
                .thenReturn(List.of(), List.of(), List.of());
        when(oddsValueEngineService.liveScoreSnapshotsForEventIds(
                argThat(ids -> ids != null && ids.contains(externalEventId)),
                anyInt(),
                eq(true)
        )).thenReturn(List.of(trackedSnapshot), List.of());
        when(ttSeriesScraper.refreshRecentOfficialResults(eq(1))).thenAnswer(invocation -> {
            Match officialResult = new Match();
            officialResult.setExternalId("official-refresh-candidate-1");
            officialResult.setDate(LocalDate.now());
            officialResult.setPlayer1(alpha);
            officialResult.setPlayer2(beta);
            MatchResultParser.applyToMatch(officialResult, "3:1");
            matchRepository.save(officialResult);
            return 1;
        });

        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.betsPlaced());
        backdateAllOpenBetStartTimes(360);

        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(0, second.betsSettled());

        PaperTradeBet trackedBet = paperTradeBetRepository.findAll().get(0);
        assertTrue(trackedBet.isTrackedAfterClose());
        TrackedMatchObservation observation = trackedMatchObservationRepository
                .findTopByBetIdOrderByObservedAtDescIdDesc(trackedBet.getId())
                .orElseThrow();
        observation.setObservedAt(LocalDateTime.now().minusMinutes(240));
        trackedMatchObservationRepository.save(observation);
        trackedBet.setLastObservedAt(LocalDateTime.now().minusMinutes(240));
        paperTradeBetRepository.save(trackedBet);

        PaperTradingSyncResultDto third = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, third.betsSettled());
        assertEquals(0, third.session().openBets());
        verify(ttSeriesScraper).refreshRecentOfficialResults(eq(1));

        PaperTradeBet settled = paperTradeBetRepository.findAll().get(0);
        assertEquals(PaperTradeBet.STATUS_WON, settled.getStatus());
        assertEquals("SETTLED_FROM_OFFICIAL_RESULT_TRACKED_AFTER_CLOSE", settled.getSettlementReason());
        assertEquals("OFFICIAL_RESULT", settled.getSettlementSource());
        assertNotNull(settled.getResultMatchId());
    }

    @Test
    void settlementIgnoresWrongEventRowWhenPairCollidesAcrossStartTimes() {
        Player alpha = playerRepository.save(new Player("Collision", "Alpha"));
        Player beta = playerRepository.save(new Player("Collision", "Beta"));
        String targetStartIso = isoDateTimeMinutesFromNow(80);
        String oldStartIso = LocalDateTime.now().minusHours(16).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Collision Alpha vs Collision Beta",
                "TTL Elite Series",
                false,
                targetStartIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.94,
                1.94,
                -106,
                -106,
                0.50,
                0.50,
                0.60,
                0.40,
                0.10,
                -0.10,
                -150,
                150,
                alpha.getName(),
                0.10,
                -150,
                0.54,
                0.67,
                true,
                "A",
                "Pair collision setup",
                "Head-to-Head Decayed",
                0.19,
                matchupKey(alpha, beta, targetStartIso),
                dedupeKey(alpha, beta, targetStartIso, alpha.getName())
        );

        LiveOddsRecommendationDto wrongEventRow = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Collision Alpha vs Collision Beta (Old Event)",
                "TTL Elite Series",
                true,
                oldStartIso,
                "3-0",
                "LIVE_LATE",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.40,
                3.00,
                -250,
                200,
                0.67,
                0.33,
                0.75,
                0.25,
                0.08,
                -0.08,
                -300,
                300,
                alpha.getName(),
                0.08,
                -300,
                0.55,
                0.72,
                true,
                "A",
                "Wrong historical event row",
                "Head-to-Head Decayed",
                0.20,
                matchupKey(alpha, beta, oldStartIso),
                dedupeKey(alpha, beta, oldStartIso, alpha.getName())
        );

        LiveOddsRecommendationDto currentNoStart = new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Collision Alpha vs Collision Beta",
                "TTL Elite Series",
                true,
                null,
                "1-1 (5-5)",
                "LIVE_MID",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.85,
                2.00,
                -118,
                100,
                0.52,
                0.48,
                0.58,
                0.42,
                0.06,
                -0.06,
                -138,
                138,
                alpha.getName(),
                0.06,
                -138,
                0.48,
                0.72,
                false,
                "B",
                "Current event still live but no start timestamp",
                "Recent Form Delta",
                0.14,
                matchupKey(alpha, beta, LocalDate.now().toString()),
                dedupeKey(alpha, beta, LocalDate.now().toString(), alpha.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch), List.of(wrongEventRow, currentNoStart));
        when(oddsValueEngineService.liveScoreSnapshots(anyInt(), eq(true)))
                .thenReturn(List.of(), List.of());

        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.betsPlaced());
        backdateAllOpenBetStartTimes(180);

        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(0, second.betsSettled());
        assertEquals(1, second.session().openBets());
        PaperTradeBet open = paperTradeBetRepository.findAll().get(0);
        assertEquals(PaperTradeBet.STATUS_OPEN, open.getStatus());
        assertFalse("3-0".equals(open.getLastObservedScore()));
    }

    @Test
    void settlementUsesTargetedScoreSnapshotForOpenEventIdWhenBoardSliceMissesMatch() {
        Player alpha = playerRepository.save(new Player("Targeted", "Alpha"));
        Player beta = playerRepository.save(new Player("Targeted", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(120);
        String externalEventId = "evt-123";

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "HARD_ROCK_GQL:FLORIDA_ONLINE|event=" + externalEventId,
                "CONSERVATIVE",
                "ENSEMBLE",
                "Targeted Alpha vs Targeted Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.90,
                1.95,
                -110,
                -105,
                0.51,
                0.49,
                0.62,
                0.38,
                0.11,
                -0.11,
                -163,
                163,
                alpha.getName(),
                0.11,
                -163,
                0.55,
                0.71,
                true,
                "A",
                "Targeted event-id settlement setup",
                "Head-to-Head Decayed",
                0.22,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        LiveScoreSnapshotDto targetedSnapshot = new LiveScoreSnapshotDto(
                "HARD_ROCK_GQL_SCORE:FLORIDA_ONLINE|event=" + externalEventId,
                "GQL_TRACKED_EVENT",
                0.97,
                0L,
                "Targeted Alpha vs Targeted Beta",
                "TTL Elite Series",
                true,
                startIso,
                "3-1",
                "LIVE_LATE",
                externalEventId,
                true,
                false,
                false,
                null,
                null,
                null,
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                matchupKey(alpha, beta, startIso)
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch), List.of());
        when(oddsValueEngineService.liveScoreSnapshots(anyInt(), eq(true)))
                .thenReturn(List.of(), List.of());
        when(oddsValueEngineService.liveScoreSnapshotsForEventIds(
                argThat(ids -> ids != null && ids.contains(externalEventId)),
                anyInt(),
                eq(true)
        )).thenReturn(List.of(targetedSnapshot));

        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.betsPlaced());
        backdateAllOpenBetStartTimes(180);

        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, second.betsSettled());
        assertEquals(0, second.session().openBets());

        PaperTradeBet settled = paperTradeBetRepository.findAll().get(0);
        assertEquals(PaperTradeBet.STATUS_WON, settled.getStatus());
        assertEquals("SETTLED_FROM_DECISIVE_LIVE_SCORE", settled.getSettlementReason());
        assertEquals("DECISIVE_LIVE_SCORE", settled.getSettlementSource());
    }

    @Test
    void identityLockBlocksConflictingRepeatMatchRebind() {
        Player alpha = playerRepository.save(new Player("Identity", "Alpha"));
        Player beta = playerRepository.save(new Player("Identity", "Beta"));
        String targetStartIso = isoDateTimeMinutesFromNow(80);
        String laterStartIso = isoDateTimeMinutesFromNow(320);

        LiveOddsRecommendationDto prematch = recommendationWithIdentity(
                alpha,
                beta,
                targetStartIso,
                false,
                null,
                "UPCOMING",
                "evt-identity-lock-1",
                "sr:match:identity-lock-1",
                "GQL_MARKET",
                "HARD_ROCK_GQL:FLORIDA_ONLINE|event=evt-identity-lock-1",
                "Identity lock setup"
        );

        LiveOddsRecommendationDto conflictingLaterMatch = recommendationWithIdentity(
                alpha,
                beta,
                laterStartIso,
                true,
                "0-2 (2-8)",
                "LIVE_MID",
                "evt-identity-lock-2",
                "sr:match:identity-lock-2",
                "GQL_SCOREBOARD",
                "HARD_ROCK_GQL_SCORE:FLORIDA_ONLINE|event=evt-identity-lock-2",
                "Conflicting later match"
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch), List.of(conflictingLaterMatch));
        when(oddsValueEngineService.liveScoreSnapshots(anyInt(), eq(true)))
                .thenReturn(List.of(), List.of());

        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.betsPlaced());
        backdateAllOpenBetStartTimes(180);

        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(0, second.betsSettled());
        assertEquals(1, second.session().openBets());

        PaperTradeBet open = paperTradeBetRepository.findAll().get(0);
        assertTrue(open.isIdentityLocked());
        assertEquals("evt-identity-lock-1", open.getLockedExternalEventId());
        assertEquals("sr:match:identity-lock-1", open.getLockedSourceFeedEventId());
        assertEquals("sr:match:identity-lock-1", open.getLastSourceFeedEventId());
        assertNull(open.getLastObservedScore());
        assertTrue(open.getIdentityDriftCount() >= 1);
        assertEquals(PaperTradeBet.STATUS_OPEN, open.getStatus());
    }

    /**
     * #114 end-to-end proof: the exact production failure mode from Session 65.
     *
     * <p>A bet is locked prematch under one feed identity (HardRock outer
     * event id {@code evt-AAA} + BETRADAR_UF inner id {@code sr:match:AAA}).
     * The match then completes and the terminal "3-1" set score arrives
     * under a DIFFERENT feed identity ({@code evt-BBB} / {@code sr:match:BBB})
     * — the cross-feed drift Hard Rock produces when its inner matchState
     * block re-keys mid-match. Same two players, same start time.
     *
     * <p>Before #114, {@code rowMatchesLockedIdentity} rejected the "3-1"
     * observation as identity drift (both ids disagree), the bet never saw
     * a terminal score, and it sat OPEN until the void timeout — settlement
     * starvation. After #114 the player-pair + start-time fallback accepts
     * the observation, {@code lastObservedScore} becomes "3-1", and the bet
     * settles WON on the next sweep.
     */
    @Test
    void crossFeedTerminalScoreSettlesBetAfterIdentityDriftFix() {
        Player alpha = playerRepository.save(new Player("CrossFeed", "Winner"));
        Player beta = playerRepository.save(new Player("CrossFeed", "Loser"));
        String startIso = isoDateTimeMinutesFromNow(90);

        // Prematch placement locks identity to feed-ID-A.
        LiveOddsRecommendationDto prematch = recommendationWithIdentity(
                alpha, beta, startIso, false, null, "UPCOMING",
                "evt-AAA", "sr:match:AAA",
                "GQL_MARKET", "HARD_ROCK_GQL:FLORIDA_ONLINE|event=evt-AAA",
                "Prematch placement locks feed-ID-A");

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch));
        when(oddsValueEngineService.liveScoreSnapshots(anyInt(), eq(true)))
                .thenReturn(List.of());
        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.betsPlaced());

        PaperTradeBet locked = paperTradeBetRepository.findAll().get(0);
        assertTrue(locked.isIdentityLocked());
        assertEquals("sr:match:AAA", locked.getLockedSourceFeedEventId());

        // Terminal "3-1" arrives under feed-ID-B — different external + feed
        // ids, identical players + start time (the cross-feed drift case).
        LiveOddsRecommendationDto terminalCrossFeed = recommendationWithIdentity(
                alpha, beta, startIso, true, "3-1", "LIVE_LATE",
                "evt-BBB", "sr:match:BBB",
                "GQL_SCOREBOARD", "HARD_ROCK_GQL_SCORE:FLORIDA_ONLINE|event=evt-BBB",
                "Terminal score under drifted feed-ID-B");

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(terminalCrossFeed));
        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        PaperTradeBet afterObs = paperTradeBetRepository.findAll().get(0);
        // The crux: #114's fallback accepted the cross-feed observation.
        assertEquals("3-1", afterObs.getLastObservedScore(),
                "#114 fallback must let the cross-feed terminal score through (was rejected as drift pre-fix)");
        assertEquals(0, afterObs.getIdentityDriftCount(),
                "cross-feed terminal score is NOT drift — same players + time");

        // Event disappears; sweep settles from the last observed "3-1".
        backdateAllOpenBetStartTimes(180);
        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of());
        PaperTradingSyncResultDto third = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

        assertEquals(1, third.betsSettled(), "bet must settle now that it has a terminal score");
        PaperTradeBet settled = paperTradeBetRepository.findAll().get(0);
        assertEquals(PaperTradeBet.STATUS_WON, settled.getStatus(),
                "alpha won 3-1 and the bet was on alpha");
    }

    @Test
    void identityLockPrefersExactFeedMatchOverLoosePairCandidate() {
        Player alpha = playerRepository.save(new Player("Identity", "PreferredAlpha"));
        Player beta = playerRepository.save(new Player("Identity", "PreferredBeta"));
        String targetStartIso = isoDateTimeMinutesFromNow(90);
        String wrongStartIso = isoDateTimeMinutesFromNow(330);

        LiveOddsRecommendationDto prematch = recommendationWithIdentity(
                alpha,
                beta,
                targetStartIso,
                false,
                null,
                "UPCOMING",
                "evt-identity-pref-1",
                "sr:match:identity-pref-1",
                "GQL_MARKET",
                "HARD_ROCK_GQL:FLORIDA_ONLINE|event=evt-identity-pref-1",
                "Identity preferred setup"
        );

        LiveOddsRecommendationDto conflictingRow = recommendationWithIdentity(
                alpha,
                beta,
                wrongStartIso,
                true,
                "2-2 (8-4)",
                "LIVE_LATE",
                "evt-identity-pref-2",
                "sr:match:identity-pref-2",
                "GQL_SCOREBOARD",
                "HARD_ROCK_GQL_SCORE:FLORIDA_ONLINE|event=evt-identity-pref-2",
                "Wrong later pairing"
        );

        LiveOddsRecommendationDto exactIdentityRow = recommendationWithIdentity(
                alpha,
                beta,
                targetStartIso,
                true,
                "1-0 (5-2)",
                "LIVE_EARLY",
                "evt-identity-pref-1",
                "sr:match:identity-pref-1",
                "GQL_TRACKED_EVENT",
                "HARD_ROCK_GQL_SCORE:FLORIDA_ONLINE|event=evt-identity-pref-1",
                "Exact locked identity row"
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch), List.of(conflictingRow, exactIdentityRow));
        when(oddsValueEngineService.liveScoreSnapshots(anyInt(), eq(true)))
                .thenReturn(List.of(), List.of());

        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.betsPlaced());

        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(0, second.betsSettled());

        PaperTradeBet open = paperTradeBetRepository.findAll().get(0);
        assertTrue(open.isIdentityLocked());
        assertEquals("sr:match:identity-pref-1", open.getLockedSourceFeedEventId());
        assertEquals("1-0 (5-2)", open.getLastObservedScore());
        assertEquals("LIVE_EARLY", open.getLastObservedPhase());
        assertEquals(0, open.getIdentityDriftCount());
    }

    @Test
    void settlementExtractsEventIdCaseInsensitivelyForTargetedScoreSnapshots() {
        Player alpha = playerRepository.save(new Player("Case", "Alpha"));
        Player beta = playerRepository.save(new Player("Case", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(120);
        String externalEventId = "evt-CASE-456";

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "HARD_ROCK_GQL:FLORIDA_ONLINE|EVENT=" + externalEventId,
                "CONSERVATIVE",
                "ENSEMBLE",
                "Case Alpha vs Case Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.92,
                1.92,
                -108,
                -108,
                0.50,
                0.50,
                0.61,
                0.39,
                0.11,
                -0.11,
                -156,
                156,
                alpha.getName(),
                0.11,
                -156,
                0.54,
                0.71,
                true,
                "A",
                "Case-insensitive event id extraction setup",
                "Head-to-Head Decayed",
                0.22,
                matchupKey(alpha, beta, startIso),
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        LiveScoreSnapshotDto targetedSnapshot = new LiveScoreSnapshotDto(
                "HARD_ROCK_GQL_SCORE:FLORIDA_ONLINE|event=" + externalEventId,
                "GQL_TRACKED_EVENT",
                0.97,
                0L,
                "Case Alpha vs Case Beta",
                "TTL Elite Series",
                true,
                startIso,
                "3-1",
                "LIVE_LATE",
                externalEventId,
                true,
                false,
                false,
                null,
                null,
                null,
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                matchupKey(alpha, beta, startIso)
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch), List.of());
        when(oddsValueEngineService.liveScoreSnapshots(anyInt(), eq(true)))
                .thenReturn(List.of(), List.of());
        when(oddsValueEngineService.liveScoreSnapshotsForEventIds(
                argThat(ids -> ids != null && ids.contains(externalEventId)),
                anyInt(),
                eq(true)
        )).thenReturn(List.of(targetedSnapshot));

        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.betsPlaced());
        backdateAllOpenBetStartTimes(180);

        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, second.betsSettled());
        assertEquals(0, second.session().openBets());

        PaperTradeBet settled = paperTradeBetRepository.findAll().get(0);
        assertEquals(PaperTradeBet.STATUS_WON, settled.getStatus());
        assertEquals("SETTLED_FROM_DECISIVE_LIVE_SCORE", settled.getSettlementReason());
        assertEquals("DECISIVE_LIVE_SCORE", settled.getSettlementSource());
    }

    @Test
    void syncCapsExposurePerPlayerAcrossMultipleCandidates() {
        Player alpha = playerRepository.save(new Player("Exposure", "Alpha"));
        Player beta = playerRepository.save(new Player("Exposure", "Beta"));
        Player gamma = playerRepository.save(new Player("Exposure", "Gamma"));

        String startIso1 = isoDateTimeMinutesFromNow(90);
        String startIso2 = isoDateTimeMinutesFromNow(110);

        LiveOddsRecommendationDto first = strongPrematchRecommendation(
                alpha,
                beta,
                startIso1,
                "Head-to-Head Decayed"
        );
        LiveOddsRecommendationDto second = strongPrematchRecommendation(
                alpha,
                gamma,
                startIso2,
                "Recent Form Delta"
        );

        double originalMaxOpenExposurePct = doubleField("maxOpenExposurePct");
        double originalMaxExposurePerPlayerPct = doubleField("maxExposurePerPlayerPct");
        double originalMaxExposurePerTriggerPct = doubleField("maxExposurePerTriggerPct");
        int originalMaxConcurrentOpenBets = intField("maxConcurrentOpenBets");

        try {
            ReflectionTestUtils.setField(paperTradingService, "maxOpenExposurePct", 0.90d);
            ReflectionTestUtils.setField(paperTradingService, "maxExposurePerPlayerPct", 0.03d);
            ReflectionTestUtils.setField(paperTradingService, "maxExposurePerTriggerPct", 0.75d);
            ReflectionTestUtils.setField(paperTradingService, "maxConcurrentOpenBets", 10);

            when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                    .thenReturn(List.of(first, second));

            PaperTradingSyncResultDto result = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

            assertEquals(2, result.rowsScanned());
            assertEquals(1, result.betsPlaced());
            assertEquals(1, result.betsSkipped());
            assertNotNull(result.session().exposureMetrics());
            assertEquals(1, result.session().exposureMetrics().playerNearCapCount());
            assertEquals(alpha.getName(), result.session().exposureMetrics().mostExposedPlayerName());
            assertEquals(1.0, result.session().exposureMetrics().mostExposedPlayerCapUsagePct(), 0.001);

            List<PaperTradeBet> bets = paperTradeBetRepository.findAll();
            assertEquals(1, bets.size());
            assertEquals(alpha.getId(), bets.get(0).getSidePlayerId());
            assertEquals(roundToCents(result.session().startingBankroll() * 0.03), bets.get(0).getStake(), 0.001);
        } finally {
            ReflectionTestUtils.setField(paperTradingService, "maxOpenExposurePct", originalMaxOpenExposurePct);
            ReflectionTestUtils.setField(paperTradingService, "maxExposurePerPlayerPct", originalMaxExposurePerPlayerPct);
            ReflectionTestUtils.setField(paperTradingService, "maxExposurePerTriggerPct", originalMaxExposurePerTriggerPct);
            ReflectionTestUtils.setField(paperTradingService, "maxConcurrentOpenBets", originalMaxConcurrentOpenBets);
        }
    }

    @Test
    void syncCapsOverallOpenExposureAcrossMultipleCandidates() {
        Player alpha = playerRepository.save(new Player("ExposureTotal", "Alpha"));
        Player beta = playerRepository.save(new Player("ExposureTotal", "Beta"));
        Player gamma = playerRepository.save(new Player("ExposureTotal", "Gamma"));
        Player delta = playerRepository.save(new Player("ExposureTotal", "Delta"));

        String startIso1 = isoDateTimeMinutesFromNow(95);
        String startIso2 = isoDateTimeMinutesFromNow(115);

        LiveOddsRecommendationDto first = strongPrematchRecommendation(
                alpha,
                beta,
                startIso1,
                "Head-to-Head Decayed"
        );
        LiveOddsRecommendationDto second = strongPrematchRecommendation(
                gamma,
                delta,
                startIso2,
                "Recent Form Delta"
        );

        double originalMaxOpenExposurePct = doubleField("maxOpenExposurePct");
        double originalMaxExposurePerPlayerPct = doubleField("maxExposurePerPlayerPct");
        double originalMaxExposurePerTriggerPct = doubleField("maxExposurePerTriggerPct");
        int originalMaxConcurrentOpenBets = intField("maxConcurrentOpenBets");

        try {
            ReflectionTestUtils.setField(paperTradingService, "maxOpenExposurePct", 0.06d);
            ReflectionTestUtils.setField(paperTradingService, "maxExposurePerPlayerPct", 0.60d);
            ReflectionTestUtils.setField(paperTradingService, "maxExposurePerTriggerPct", 0.75d);
            ReflectionTestUtils.setField(paperTradingService, "maxConcurrentOpenBets", 10);

            when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                    .thenReturn(List.of(first, second));

            PaperTradingSyncResultDto result = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

            assertEquals(2, result.rowsScanned());
            assertEquals(2, result.betsPlaced());
            assertEquals(0, result.betsSkipped());
            assertNotNull(result.session().exposureMetrics());
            assertEquals(1.0, result.session().exposureMetrics().openExposureUsagePct(), 0.001);

            List<PaperTradeBet> bets = paperTradeBetRepository.findAll();
            assertEquals(2, bets.size());
            double totalStake = bets.stream().mapToDouble(PaperTradeBet::getStake).sum();
            assertEquals(roundToCents(result.session().startingBankroll() * 0.10), roundToCents(totalStake), 0.001);
        } finally {
            ReflectionTestUtils.setField(paperTradingService, "maxOpenExposurePct", originalMaxOpenExposurePct);
            ReflectionTestUtils.setField(paperTradingService, "maxExposurePerPlayerPct", originalMaxExposurePerPlayerPct);
            ReflectionTestUtils.setField(paperTradingService, "maxExposurePerTriggerPct", originalMaxExposurePerTriggerPct);
            ReflectionTestUtils.setField(paperTradingService, "maxConcurrentOpenBets", originalMaxConcurrentOpenBets);
        }
    }

    @Test
    void syncCapsExposurePerTriggerAcrossMultipleCandidates() {
        Player alpha = playerRepository.save(new Player("ExposureTrigger", "Alpha"));
        Player beta = playerRepository.save(new Player("ExposureTrigger", "Beta"));
        Player gamma = playerRepository.save(new Player("ExposureTrigger", "Gamma"));
        Player delta = playerRepository.save(new Player("ExposureTrigger", "Delta"));

        String startIso1 = isoDateTimeMinutesFromNow(100);
        String startIso2 = isoDateTimeMinutesFromNow(120);

        LiveOddsRecommendationDto first = strongPrematchRecommendation(
                alpha,
                beta,
                startIso1,
                "Head-to-Head Decayed"
        );
        LiveOddsRecommendationDto second = strongPrematchRecommendation(
                gamma,
                delta,
                startIso2,
                "Head-to-Head Decayed"
        );

        double originalMaxOpenExposurePct = doubleField("maxOpenExposurePct");
        double originalMaxExposurePerPlayerPct = doubleField("maxExposurePerPlayerPct");
        double originalMaxExposurePerTriggerPct = doubleField("maxExposurePerTriggerPct");
        int originalMaxConcurrentOpenBets = intField("maxConcurrentOpenBets");

        try {
            ReflectionTestUtils.setField(paperTradingService, "maxOpenExposurePct", 0.90d);
            ReflectionTestUtils.setField(paperTradingService, "maxExposurePerPlayerPct", 0.60d);
            ReflectionTestUtils.setField(paperTradingService, "maxExposurePerTriggerPct", 0.05d);
            ReflectionTestUtils.setField(paperTradingService, "maxConcurrentOpenBets", 10);

            when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                    .thenReturn(List.of(first, second));

            PaperTradingSyncResultDto result = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);

            assertEquals(2, result.rowsScanned());
            assertEquals(1, result.betsPlaced());
            assertEquals(1, result.betsSkipped());
            assertNotNull(result.session().exposureMetrics());
            assertEquals(1, result.session().exposureMetrics().triggerNearCapCount());
            assertEquals("head-to-head decayed", result.session().exposureMetrics().mostExposedTrigger());
            assertEquals(1.0, result.session().exposureMetrics().mostExposedTriggerCapUsagePct(), 0.001);

            List<PaperTradeBet> bets = paperTradeBetRepository.findAll();
            assertEquals(1, bets.size());
            assertEquals(roundToCents(result.session().startingBankroll() * 0.05), bets.get(0).getStake(), 0.001);
            assertEquals("Head-to-Head Decayed", bets.get(0).getTopTrigger());
        } finally {
            ReflectionTestUtils.setField(paperTradingService, "maxOpenExposurePct", originalMaxOpenExposurePct);
            ReflectionTestUtils.setField(paperTradingService, "maxExposurePerPlayerPct", originalMaxExposurePerPlayerPct);
            ReflectionTestUtils.setField(paperTradingService, "maxExposurePerTriggerPct", originalMaxExposurePerTriggerPct);
            ReflectionTestUtils.setField(paperTradingService, "maxConcurrentOpenBets", originalMaxConcurrentOpenBets);
        }
    }

    @Test
    void targetedResultedSignalWithUnchangedScoreStillPersistsObservationAndSettles() {
        Player alpha = playerRepository.save(new Player("Resulted", "Alpha"));
        Player beta = playerRepository.save(new Player("Resulted", "Beta"));
        String placementStartIso = isoDateTimeMinutesFromNow(45);
        String eventKey = matchupKey(alpha, beta, placementStartIso);
        String externalEventId = "evt-targeted-resulted-1";
        String trackedStartIso = LocalDateTime.now().minusMinutes(95).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String resultedStartIso = LocalDateTime.now().minusMinutes(85).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "HARD_ROCK_GQL:FLORIDA_ONLINE|event=" + externalEventId,
                "CONSERVATIVE",
                "ENSEMBLE",
                "Resulted Alpha vs Resulted Beta",
                "TTL Elite Series",
                false,
                placementStartIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.92,
                1.98,
                -109,
                -101,
                0.51,
                0.49,
                0.61,
                0.39,
                0.10,
                -0.10,
                -156,
                156,
                alpha.getName(),
                0.10,
                -156,
                0.55,
                0.68,
                true,
                "A",
                "Targeted resulted unchanged-score setup",
                "Head-to-Head Decayed",
                0.22,
                eventKey,
                dedupeKey(alpha, beta, placementStartIso, alpha.getName())
        );

        LiveScoreSnapshotDto trackedSnapshot = new LiveScoreSnapshotDto(
                "HARD_ROCK_GQL_SCORE:FLORIDA_ONLINE|event=" + externalEventId,
                "GQL_TRACKED_EVENT",
                0.96,
                0L,
                "Resulted Alpha vs Resulted Beta",
                "TTL Elite Series",
                true,
                trackedStartIso,
                "2-2 (10-8)",
                "LIVE_LATE",
                externalEventId,
                false,
                false,
                false,
                "BETRADAR_UF",
                "sr:match:72000103",
                "11-9, 8-11, 11-7, 7-11, 10-8",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                eventKey
        );

        LiveScoreSnapshotDto resultedSnapshot = new LiveScoreSnapshotDto(
                "HARD_ROCK_GQL_SCORE:FLORIDA_ONLINE|event=" + externalEventId,
                "GQL_TRACKED_EVENT",
                0.99,
                0L,
                "Resulted Alpha vs Resulted Beta",
                "TTL Elite Series",
                true,
                resultedStartIso,
                "2-2 (10-8)",
                "LIVE_LATE",
                externalEventId,
                false,
                true,
                false,
                "BETRADAR_UF",
                "sr:match:72000103",
                "11-9, 8-11, 11-7, 7-11, 10-8",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                eventKey
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch), List.of(), List.of());
        when(oddsValueEngineService.liveScoreSnapshots(anyInt(), eq(true)))
                .thenReturn(List.of(), List.of(), List.of());
        when(oddsValueEngineService.liveScoreSnapshotsForEventIds(
                argThat(ids -> ids != null && ids.contains(externalEventId)),
                anyInt(),
                eq(true)
        )).thenReturn(List.of(trackedSnapshot), List.of(resultedSnapshot));

        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.betsPlaced());
        backdateAllOpenBetStartTimes(300);

        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(0, second.betsSettled());
        assertEquals(1, second.session().openBets());

        PaperTradingSyncResultDto third = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, third.betsSettled());
        assertEquals(0, third.session().openBets());

        PaperTradeBet settled = paperTradeBetRepository.findAll().get(0);
        assertEquals(PaperTradeBet.STATUS_WON, settled.getStatus());
        assertEquals("SETTLED_FROM_TARGETED_MATCH_COMPLETED", settled.getSettlementReason());
        assertEquals("DECISIVE_LIVE_SCORE", settled.getSettlementSource());
        assertFalse(settled.isLastMatchCompleted());
        assertTrue(settled.isLastObservationResulted());
        assertEquals("2-2 (10-8)", settled.getLastObservedScore());

        List<TrackedMatchObservationDto> timeline = paperTradingService.getMatchTimeline(eventKey);
        assertEquals(3, timeline.size());
        TrackedMatchObservationDto latest = timeline.get(timeline.size() - 1);
        assertEquals("2-2 (10-8)", latest.liveScore());
        assertFalse(latest.matchCompleted());
        assertTrue(latest.resulted());
    }

    @Test
    void officialResultSettlementIgnoresDuplicateFeedIdentityOnDifferentPlayers() {
        Player alpha = playerRepository.save(new Player("FeedDup", "Alpha"));
        Player beta = playerRepository.save(new Player("FeedDup", "Beta"));
        Player gamma = playerRepository.save(new Player("FeedDup", "Gamma"));
        Player delta = playerRepository.save(new Player("FeedDup", "Delta"));
        String placementStartIso = isoDateTimeMinutesFromNow(35);
        String trackedStartIso = LocalDateTime.now().minusMinutes(120).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String externalEventId = "evt-feed-dup-1";
        String sharedFeedEventId = "sr:match:71110099";
        String eventKey = matchupKey(alpha, beta, placementStartIso);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "HARD_ROCK_GQL:FLORIDA_ONLINE|event=" + externalEventId,
                "CONSERVATIVE",
                "ENSEMBLE",
                "FeedDup Alpha vs FeedDup Beta",
                "TTL Elite Series",
                false,
                placementStartIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.94,
                1.96,
                -106,
                -104,
                0.50,
                0.50,
                0.59,
                0.41,
                0.09,
                -0.09,
                -150,
                150,
                alpha.getName(),
                0.09,
                -150,
                0.54,
                0.67,
                true,
                "B",
                "Feed identity duplicate setup",
                "Recent Form Delta",
                0.21,
                eventKey,
                dedupeKey(alpha, beta, placementStartIso, alpha.getName())
        );

        LiveScoreSnapshotDto trackedSnapshot = new LiveScoreSnapshotDto(
                "HARD_ROCK_GQL_SCORE:FLORIDA_ONLINE|event=" + externalEventId,
                "GQL_TRACKED_EVENT",
                0.92,
                0L,
                "FeedDup Alpha vs FeedDup Beta",
                "TTL Elite Series",
                true,
                trackedStartIso,
                "2-2 (9-9)",
                "LIVE_LATE",
                externalEventId,
                false,
                false,
                false,
                "BETRADAR_UF",
                sharedFeedEventId,
                "11-8, 9-11, 11-7, 8-11",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                eventKey
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch), List.of(), List.of());
        when(oddsValueEngineService.liveScoreSnapshots(anyInt(), eq(true)))
                .thenReturn(List.of(), List.of(), List.of());
        when(oddsValueEngineService.liveScoreSnapshotsForEventIds(
                argThat(ids -> ids != null && ids.contains(externalEventId)),
                anyInt(),
                eq(true)
        )).thenReturn(List.of(trackedSnapshot), List.of());

        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.betsPlaced());
        backdateAllOpenBetStartTimes(360);

        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(0, second.betsSettled());

        PaperTradeBet trackedBet = paperTradeBetRepository.findAll().get(0);
        TrackedMatchObservation observation = trackedMatchObservationRepository
                .findTopByBetIdOrderByObservedAtDescIdDesc(trackedBet.getId())
                .orElseThrow();
        observation.setObservedAt(LocalDateTime.now().minusMinutes(240));
        trackedMatchObservationRepository.save(observation);
        trackedBet.setLastObservedAt(LocalDateTime.now().minusMinutes(240));
        paperTradeBetRepository.save(trackedBet);

        Match wrongFeedIdentity = new Match();
        wrongFeedIdentity.setExternalId("wrong-feed-identity-duplicate");
        wrongFeedIdentity.setSourceFeedCode("BETRADAR_UF");
        wrongFeedIdentity.setSourceFeedEventId(sharedFeedEventId);
        wrongFeedIdentity.setDate(LocalDate.now());
        wrongFeedIdentity.setPlayer1(gamma);
        wrongFeedIdentity.setPlayer2(delta);
        MatchResultParser.applyToMatch(wrongFeedIdentity, "3:0");
        matchRepository.save(wrongFeedIdentity);

        Match correctOfficialResult = new Match();
        correctOfficialResult.setExternalId("correct-official-result");
        correctOfficialResult.setDate(LocalDate.now());
        correctOfficialResult.setPlayer1(alpha);
        correctOfficialResult.setPlayer2(beta);
        MatchResultParser.applyToMatch(correctOfficialResult, "3:1");
        matchRepository.save(correctOfficialResult);

        PaperTradingSyncResultDto third = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, third.betsSettled());
        assertEquals(0, third.session().openBets());

        PaperTradeBet settled = paperTradeBetRepository.findAll().get(0);
        assertEquals(PaperTradeBet.STATUS_WON, settled.getStatus());
        assertEquals("SETTLED_FROM_OFFICIAL_RESULT_TRACKED_AFTER_CLOSE", settled.getSettlementReason());
        assertEquals("OFFICIAL_RESULT", settled.getSettlementSource());
        assertEquals(correctOfficialResult.getId(), settled.getResultMatchId());
    }

    @Test
    void targetedCompletedSnapshotOutranksConflictingOfficialResultWhileFeedStillObservable() {
        Player alpha = playerRepository.save(new Player("Priority", "Alpha"));
        Player beta = playerRepository.save(new Player("Priority", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(120);
        String externalEventId = "evt-priority-targeted-1";
        String eventKey = matchupKey(alpha, beta, startIso);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "HARD_ROCK_GQL:FLORIDA_ONLINE|event=" + externalEventId,
                "CONSERVATIVE",
                "ENSEMBLE",
                "Priority Alpha vs Priority Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.90,
                1.95,
                -110,
                -105,
                0.51,
                0.49,
                0.62,
                0.38,
                0.11,
                -0.11,
                -163,
                163,
                alpha.getName(),
                0.11,
                -163,
                0.55,
                0.71,
                true,
                "A",
                "Targeted priority settlement setup",
                "Head-to-Head Decayed",
                0.22,
                eventKey,
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        LiveScoreSnapshotDto targetedCompletedSnapshot = new LiveScoreSnapshotDto(
                "HARD_ROCK_GQL_SCORE:FLORIDA_ONLINE|event=" + externalEventId,
                "GQL_TRACKED_EVENT",
                0.97,
                0L,
                "Priority Alpha vs Priority Beta",
                "TTL Elite Series",
                true,
                startIso,
                "3-1",
                "LIVE_LATE",
                externalEventId,
                false,
                false,
                true,
                "BETRADAR_UF",
                "sr:match:72000104",
                "11-8, 9-11, 11-6, 11-7",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                eventKey
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch), List.of());
        when(oddsValueEngineService.liveScoreSnapshots(anyInt(), eq(true)))
                .thenReturn(List.of(), List.of(targetedCompletedSnapshot));
        when(oddsValueEngineService.liveScoreSnapshotsForEventIds(
                argThat(ids -> ids != null && ids.contains(externalEventId)),
                anyInt(),
                eq(true)
        )).thenReturn(List.of(targetedCompletedSnapshot));

        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.betsPlaced());
        backdateAllOpenBetStartTimes(180);

        Match conflictingOfficialResult = new Match();
        conflictingOfficialResult.setExternalId("priority-conflicting-official");
        conflictingOfficialResult.setDate(LocalDate.now());
        conflictingOfficialResult.setPlayer1(alpha);
        conflictingOfficialResult.setPlayer2(beta);
        MatchResultParser.applyToMatch(conflictingOfficialResult, "1:3");
        matchRepository.save(conflictingOfficialResult);

        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, second.betsSettled());
        assertEquals(0, second.session().openBets());

        PaperTradeBet settled = paperTradeBetRepository.findAll().get(0);
        assertEquals(PaperTradeBet.STATUS_WON, settled.getStatus());
        assertEquals("SETTLED_FROM_TARGETED_MATCH_COMPLETED", settled.getSettlementReason());
        assertEquals("DECISIVE_LIVE_SCORE", settled.getSettlementSource());
        assertNull(settled.getResultMatchId());
    }

    @Test
    void ambiguousSameDayOfficialResultsDoNotAutoSettle() {
        Player alpha = playerRepository.save(new Player("Ambiguous", "Alpha"));
        Player beta = playerRepository.save(new Player("Ambiguous", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(120);
        String eventKey = matchupKey(alpha, beta, startIso);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "HARD_ROCK_GQL:FLORIDA_ONLINE|event=evt-ambiguous-official-1",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Ambiguous Alpha vs Ambiguous Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.92,
                1.94,
                -108,
                -106,
                0.5,
                0.5,
                0.61,
                0.39,
                0.10,
                -0.10,
                -156,
                156,
                alpha.getName(),
                0.10,
                -156,
                0.54,
                0.68,
                true,
                "A",
                "Ambiguous official result setup",
                "Head-to-Head Decayed",
                0.24,
                eventKey,
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch), List.of());
        when(oddsValueEngineService.liveScoreSnapshots(anyInt(), eq(true)))
                .thenReturn(List.of(), List.of());
        when(oddsValueEngineService.liveScoreSnapshotsForEventIds(argThat(ids -> ids != null && !ids.isEmpty()), anyInt(), eq(true)))
                .thenReturn(List.of(), List.of());

        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.betsPlaced());
        backdateAllOpenBetStartTimes(240);

        PaperTradeBet bet = paperTradeBetRepository.findAll().get(0);
        bet.setLastObservedScore("0-2 (6-10)");
        bet.setLastObservedPhase("LIVE_MID");
        paperTradeBetRepository.save(bet);

        Match firstOfficial = new Match();
        firstOfficial.setExternalId("ambiguous-official-a");
        firstOfficial.setDate(LocalDate.now());
        firstOfficial.setPlayer1(alpha);
        firstOfficial.setPlayer2(beta);
        MatchResultParser.applyToMatch(firstOfficial, "3:1");
        matchRepository.save(firstOfficial);

        Match secondOfficial = new Match();
        secondOfficial.setExternalId("ambiguous-official-b");
        secondOfficial.setDate(LocalDate.now());
        secondOfficial.setPlayer1(alpha);
        secondOfficial.setPlayer2(beta);
        MatchResultParser.applyToMatch(secondOfficial, "1:3");
        matchRepository.save(secondOfficial);

        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(0, second.betsSettled());
        assertEquals(1, second.session().openBets());

        PaperTradeBet unresolved = paperTradeBetRepository.findAll().get(0);
        assertEquals(PaperTradeBet.STATUS_OPEN, unresolved.getStatus());
        assertNull(unresolved.getResultMatchId());
        assertNull(unresolved.getSettlementReason());
        assertNull(unresolved.getSettlementSource());
    }

    @Test
    void conflictingLaterDateOfficialResultDoesNotAutoSettleAgainstStrongLiveLeader() {
        Player alpha = playerRepository.save(new Player("Later", "Alpha"));
        Player beta = playerRepository.save(new Player("Later", "Beta"));
        String startIso = isoDateTimeMinutesFromNow(120);
        String eventKey = matchupKey(alpha, beta, startIso);

        LiveOddsRecommendationDto prematch = new LiveOddsRecommendationDto(
                "HARD_ROCK_GQL:FLORIDA_ONLINE|event=evt-later-official-1",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Later Alpha vs Later Beta",
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                alpha.getId(),
                alpha.getName(),
                beta.getId(),
                beta.getName(),
                1.95,
                1.91,
                -105,
                -110,
                0.5,
                0.5,
                0.60,
                0.40,
                0.11,
                -0.11,
                -150,
                150,
                alpha.getName(),
                0.11,
                -150,
                0.55,
                0.69,
                true,
                "A",
                "Later official contradiction setup",
                "Head-to-Head Decayed",
                0.24,
                eventKey,
                dedupeKey(alpha, beta, startIso, alpha.getName())
        );

        when(oddsValueEngineService.liveOddsRecommendations(eq("CONSERVATIVE"), eq("ENSEMBLE"), anyInt(), eq(false)))
                .thenReturn(List.of(prematch), List.of());
        when(oddsValueEngineService.liveScoreSnapshots(anyInt(), eq(true)))
                .thenReturn(List.of(), List.of());
        when(oddsValueEngineService.liveScoreSnapshotsForEventIds(argThat(ids -> ids != null && !ids.isEmpty()), anyInt(), eq(true)))
                .thenReturn(List.of(), List.of());

        PaperTradingSyncResultDto first = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(1, first.betsPlaced());

        PaperTradeBet bet = paperTradeBetRepository.findAll().get(0);
        String priorDayStartIso = LocalDateTime.now()
                .minusDays(1)
                .withHour(20)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        bet.setStartTimeIso(priorDayStartIso);
        bet.setLastObservedScore("0-2 (6-10)");
        bet.setLastObservedPhase("LIVE_MID");
        paperTradeBetRepository.save(bet);

        Match conflictingLaterDay = new Match();
        conflictingLaterDay.setExternalId("later-day-official-conflict");
        conflictingLaterDay.setDate(LocalDate.now());
        conflictingLaterDay.setPlayer1(alpha);
        conflictingLaterDay.setPlayer2(beta);
        MatchResultParser.applyToMatch(conflictingLaterDay, "3:1");
        matchRepository.save(conflictingLaterDay);

        PaperTradingSyncResultDto second = paperTradingService.syncLiveSession("CONSERVATIVE", "ENSEMBLE", 30);
        assertEquals(0, second.betsSettled());
        assertEquals(1, second.session().openBets());

        PaperTradeBet unresolved = paperTradeBetRepository.findAll().get(0);
        assertEquals(PaperTradeBet.STATUS_OPEN, unresolved.getStatus());
        assertNull(unresolved.getResultMatchId());
        assertNull(unresolved.getSettlementReason());
        assertNull(unresolved.getSettlementSource());
    }

    private void backdateAllOpenBetStartTimes(long minutesAgo) {
        String backdatedStartIso = LocalDateTime.now()
                .minusMinutes(Math.max(1, minutesAgo))
                .withSecond(0)
                .withNano(0)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        List<PaperTradeBet> openBets = paperTradeBetRepository.findAll()
                .stream()
                .filter(bet -> PaperTradeBet.STATUS_OPEN.equals(bet.getStatus()))
                .toList();
        for (PaperTradeBet bet : openBets) {
            bet.setStartTimeIso(backdatedStartIso);
            paperTradeBetRepository.save(bet);
        }
    }

    private static String isoDateTimeMinutesFromNow(long minutesAhead) {
        return LocalDateTime.now()
                .plusMinutes(Math.max(1, minutesAhead))
                .withSecond(0)
                .withNano(0)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private static LiveOddsRecommendationDto marketRow(Long player1Id,
                                                       Long player2Id,
                                                       double player1Implied,
                                                       double player2Implied) {
        return new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                "Market Alpha vs Market Beta",
                "TTL Elite Series",
                false,
                LocalDate.now().plusDays(1).toString(),
                null,
                "UPCOMING",
                player1Id,
                "Market Alpha",
                player2Id,
                "Market Beta",
                1.67,
                2.00,
                -149,
                100,
                player1Implied,
                player2Implied,
                0.64,
                0.36,
                0.04,
                -0.14,
                -178,
                178,
                "Market Alpha",
                0.04,
                -178,
                0.55,
                0.73,
                true,
                "A",
                "Two-sided market fixture",
                "Glicko Rating Delta",
                0.20,
                "market-alpha|market-beta|fixture",
                "market-alpha|market-beta|fixture|market-alpha"
        );
    }

    private static String matchupKey(Player p1, Player p2, String startIso) {
        String left = p1.getId() <= p2.getId() ? "id-" + p1.getId() : "id-" + p2.getId();
        String right = p1.getId() <= p2.getId() ? "id-" + p2.getId() : "id-" + p1.getId();
        String bucket = StringUtils.hasText(startIso) ? normalizeToken(startIso.length() >= 16 && startIso.contains("T") ? startIso.substring(0, 16) : startIso) : "na";
        return left + "|" + right + "|" + bucket;
    }

    private static String dedupeKey(Player p1, Player p2, String startIso, String sideName) {
        return matchupKey(p1, p2, startIso) + "|" + normalizeToken(sideName);
    }

    private PaperTradeSession saveActiveSession(String label, double bankroll) {
        PaperTradeSession session = new PaperTradeSession();
        session.setStatus(PaperTradeSession.STATUS_ACTIVE);
        session.setLabel(label);
        session.setStartingBankroll(bankroll);
        session.setCurrentBankroll(bankroll);
        session.setPeakBankroll(bankroll);
        session.setRealizedPnl(0.0);
        session.setTotalStaked(0.0);
        session.setTotalReturned(0.0);
        session.setTotalBets(0);
        session.setWins(0);
        session.setLosses(0);
        session.setPushes(0);
        session.setSimulationRowsScanned(0L);
        session.setSimulationBetsPlaced(0L);
        session.setSimulationBetsSettled(0L);
        session.setSimulationBetsVoided(0L);
        session.setAdaptiveSampleSize(0);
        session.setAdaptiveEdgeShift(0.0);
        session.setAdaptiveSelectionScoreShift(0.0);
        session.setAdaptiveStakeMultiplier(1.0);
        session.setAdaptiveCalibrationError(0.0);
        session.setAdaptiveRoiSignal(0.0);
        return paperTradeSessionRepository.save(session);
    }

    private LiveOddsRecommendationDto strongPrematchRecommendation(Player player1,
                                                                   Player player2,
                                                                   String startIso,
                                                                   String trigger) {
        return new LiveOddsRecommendationDto(
                "TEST_BOOK",
                "CONSERVATIVE",
                "ENSEMBLE",
                player1.getName() + " vs " + player2.getName(),
                "TTL Elite Series",
                false,
                startIso,
                null,
                "UPCOMING",
                player1.getId(),
                player1.getName(),
                player2.getId(),
                player2.getName(),
                2.10,
                1.78,
                110,
                -128,
                0.47,
                0.53,
                0.68,
                0.32,
                0.21,
                -0.21,
                -213,
                213,
                player1.getName(),
                0.21,
                -213,
                0.58,
                0.76,
                true,
                "A",
                "Strong exposure-cap regression candidate",
                trigger,
                0.32,
                matchupKey(player1, player2, startIso),
                dedupeKey(player1, player2, startIso, player1.getName())
        );
    }

    private LiveOddsRecommendationDto recommendationWithIdentity(Player player1,
                                                                 Player player2,
                                                                 String startIso,
                                                                 boolean live,
                                                                 String liveScore,
                                                                 String matchPhase,
                                                                 String externalEventId,
                                                                 String sourceFeedEventId,
                                                                 String sourceType,
                                                                 String source,
                                                                 String rationale) {
        return new LiveOddsRecommendationDto(
                source,
                "CONSERVATIVE",
                "ENSEMBLE",
                player1.getName() + " vs " + player2.getName(),
                "TTL Elite Series",
                live,
                startIso,
                liveScore,
                matchPhase,
                player1.getId(),
                player1.getName(),
                player2.getId(),
                player2.getName(),
                1.94,
                1.94,
                -106,
                -106,
                0.50,
                0.50,
                0.60,
                0.40,
                0.10,
                -0.10,
                -150,
                150,
                player1.getName(),
                0.10,
                -150,
                0.54,
                0.67,
                true,
                "A",
                rationale,
                "Head-to-Head Decayed",
                0.19,
                null,
                null,
                null,
                null,
                matchupKey(player1, player2, startIso),
                dedupeKey(player1, player2, startIso, player1.getName()),
                sourceType,
                0.95,
                externalEventId,
                true,
                false,
                false,
                "BETRADAR_UF",
                sourceFeedEventId,
                null
        );
    }

    private int intField(String name) {
        return ((Number) ReflectionTestUtils.getField(paperTradingService, name)).intValue();
    }

    private double doubleField(String name) {
        return ((Number) ReflectionTestUtils.getField(paperTradingService, name)).doubleValue();
    }

    private static double roundToCents(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String normalizeToken(String value) {
        return value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }
}
