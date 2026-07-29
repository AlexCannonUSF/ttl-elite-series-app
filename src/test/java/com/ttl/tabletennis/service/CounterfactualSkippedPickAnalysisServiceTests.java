package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.PaperTradeDecisionSample;
import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.dto.CounterfactualSkippedReportDto;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.PaperTradeDecisionSampleRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CounterfactualSkippedPickAnalysisServiceTests {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-05-24T18:00:00Z"),
            ZoneOffset.UTC);

    @Test
    void attributesWinAndLossToSkippedPicksByReasonAndComputesCounterfactualPnl() {
        PaperTradeDecisionSampleRepository decisionRepo = mock(PaperTradeDecisionSampleRepository.class);
        MatchRepository matchRepo = mock(MatchRepository.class);

        // Two skipped picks for the SAME reason: one would have won, one would have lost.
        PaperTradeDecisionSample wouldWin = sample(
                /*id*/ 1L,
                /*p1*/ 10L, "Alpha",
                /*p2*/ 20L, "Beta",
                /*side*/ 10L, "Alpha",
                /*modelProb*/ 0.62,
                /*impliedProb*/ 0.50,
                /*edge*/ 0.12,
                /*american*/ 150,            // decimal 2.50
                /*stake*/ 40.0,
                /*reason*/ "EDGE_BELOW_THRESHOLD",
                /*createdAt*/ LocalDateTime.parse("2026-05-22T12:00:00"));
        PaperTradeDecisionSample wouldLose = sample(
                /*id*/ 2L,
                /*p1*/ 30L, "Gamma",
                /*p2*/ 40L, "Delta",
                /*side*/ 30L, "Gamma",
                /*modelProb*/ 0.55,
                /*impliedProb*/ 0.48,
                /*edge*/ 0.07,
                /*american*/ -110,           // decimal ≈ 1.909
                /*stake*/ 50.0,
                /*reason*/ "EDGE_BELOW_THRESHOLD",
                /*createdAt*/ LocalDateTime.parse("2026-05-22T13:00:00"));
        // One undecided (no matching match).
        PaperTradeDecisionSample undecided = sample(
                /*id*/ 3L,
                /*p1*/ 50L, "Eps",
                /*p2*/ 60L, "Zeta",
                /*side*/ 60L, "Zeta",
                /*modelProb*/ 0.51,
                /*impliedProb*/ 0.47,
                /*edge*/ 0.04,
                /*american*/ 120,
                /*stake*/ 25.0,
                /*reason*/ "CONFIDENCE_TOO_WIDE",
                /*createdAt*/ LocalDateTime.parse("2026-05-23T09:00:00"));

        when(decisionRepo.findByDecisionStatusAndCreatedAtAfterOrderByCreatedAtAsc(
                eq("SKIPPED"), any(LocalDateTime.class)))
                .thenReturn(List.of(wouldWin, wouldLose, undecided));

        // First skip: side 10 (Alpha) won.
        Match winMatch = match(10L, 20L, /*winnerId*/ 10L, LocalDate.parse("2026-05-22"));
        when(matchRepo.findByPlayersAndDate(eq(10L), eq(20L), eq(LocalDate.parse("2026-05-22"))))
                .thenReturn(Optional.of(winMatch));

        // Second skip: side 30 (Gamma) lost — Delta won.
        Match lossMatch = match(30L, 40L, /*winnerId*/ 40L, LocalDate.parse("2026-05-22"));
        when(matchRepo.findByPlayersAndDate(eq(30L), eq(40L), eq(LocalDate.parse("2026-05-22"))))
                .thenReturn(Optional.of(lossMatch));

        // Third skip: no match found in any of the ±2-day windows.
        when(matchRepo.findByPlayersAndDate(eq(50L), eq(60L), any(LocalDate.class)))
                .thenReturn(Optional.empty());

        CounterfactualSkippedPickAnalysisService svc = new CounterfactualSkippedPickAnalysisService(
                decisionRepo, matchRepo, FIXED_CLOCK);

        CounterfactualSkippedReportDto report = svc.analyze(14);

        assertEquals(14, report.lookbackDays());
        assertEquals(3, report.totalSkipped());
        assertEquals(2, report.decided());
        assertEquals(1, report.undecided());
        assertEquals(1, report.wins());
        assertEquals(1, report.losses());

        // wouldWin: stake 40 × (2.50 - 1) = +60.00
        // wouldLose: stake 50 × -1     = -50.00
        // total counterfactual = +10.00 on 90 staked → ROI 11.11%
        assertEquals(10.0, report.counterfactualPnL(), 0.01);
        assertEquals(11.11, report.counterfactualRoiPct(), 0.05);

        // Per-reason: EDGE_BELOW_THRESHOLD has both decided rows.
        CounterfactualSkippedReportDto.ReasonBreakdownDto edgeRow = report.byReason().stream()
                .filter(r -> r.reason().equals("EDGE_BELOW_THRESHOLD"))
                .findFirst()
                .orElseThrow();
        assertEquals(2, edgeRow.totalSkipped());
        assertEquals(2, edgeRow.decided());
        assertEquals(1, edgeRow.wins());
        assertEquals(1, edgeRow.losses());
        assertEquals(10.0, edgeRow.counterfactualPnL(), 0.01);
        assertEquals(50.0, edgeRow.winRatePct(), 0.01);

        CounterfactualSkippedReportDto.ReasonBreakdownDto wideRow = report.byReason().stream()
                .filter(r -> r.reason().equals("CONFIDENCE_TOO_WIDE"))
                .findFirst()
                .orElseThrow();
        assertEquals(1, wideRow.totalSkipped());
        assertEquals(0, wideRow.decided());
        assertEquals(0, wideRow.counterfactualPnL(), 0.001);

        // Most-impactful-reason first ordering — the EDGE_BELOW_THRESHOLD has
        // bigger absolute counterfactual P/L than the all-undecided
        // CONFIDENCE_TOO_WIDE row, so it should come first.
        assertEquals("EDGE_BELOW_THRESHOLD", report.byReason().get(0).reason());
    }

    @Test
    void returnsEmptyReportWhenNoSkippedSamplesExist() {
        PaperTradeDecisionSampleRepository decisionRepo = mock(PaperTradeDecisionSampleRepository.class);
        MatchRepository matchRepo = mock(MatchRepository.class);
        when(decisionRepo.findByDecisionStatusAndCreatedAtAfterOrderByCreatedAtAsc(
                anyString(), any(LocalDateTime.class)))
                .thenReturn(List.of());

        CounterfactualSkippedPickAnalysisService svc = new CounterfactualSkippedPickAnalysisService(
                decisionRepo, matchRepo, FIXED_CLOCK);

        CounterfactualSkippedReportDto report = svc.analyze(7);
        assertNotNull(report);
        assertEquals(0, report.totalSkipped());
        assertEquals(0, report.decided());
        assertEquals(0, report.undecided());
        assertEquals(0.0, report.counterfactualPnL(), 0.001);
        assertTrue(report.byReason().isEmpty());
    }

    @Test
    void fallsBackToSessionAverageStakeWhenSkippedPickHasNullStake() {
        // Realistic scenario from production: most SKIP reasons fire before
        // the stake math runs, so proposedStake is null in the DB. With no
        // fallback the counterfactual P/L would always be $0 — useless.
        PaperTradeDecisionSampleRepository decisionRepo = mock(PaperTradeDecisionSampleRepository.class);
        MatchRepository matchRepo = mock(MatchRepository.class);

        // One skipped pick with NULL stake (the production case)
        PaperTradeDecisionSample skipped = sample(
                1L, 10L, "A", 20L, "B", 10L, "A",
                0.60, 0.50, 0.10,
                /*american*/ 200,            // decimal 3.00
                /*stake*/ null,              // <-- the bug: stake is null
                "DUPLICATE_OPEN_EVENT",
                LocalDateTime.parse("2026-05-23T10:00:00"));

        // Two PLACED bets to seed the fallback average
        PaperTradeDecisionSample placed1 = sample(
                2L, 30L, "C", 40L, "D", 30L, "C",
                0.65, 0.50, 0.15, 150, /*stake*/ 60.0,
                "ACCEPTED",
                LocalDateTime.parse("2026-05-23T08:00:00"));
        placed1.setDecisionStatus("PLACED");
        placed1.setCappedStake(60.0);
        PaperTradeDecisionSample placed2 = sample(
                3L, 50L, "E", 60L, "F", 50L, "E",
                0.62, 0.50, 0.12, 100, /*stake*/ 40.0,
                "ACCEPTED",
                LocalDateTime.parse("2026-05-23T09:00:00"));
        placed2.setDecisionStatus("PLACED");
        placed2.setCappedStake(40.0);

        when(decisionRepo.findByDecisionStatusAndCreatedAtAfterOrderByCreatedAtAsc(eq("SKIPPED"), any(LocalDateTime.class)))
                .thenReturn(List.of(skipped));
        when(decisionRepo.findByDecisionStatusAndCreatedAtAfterOrderByCreatedAtAsc(eq("PLACED"), any(LocalDateTime.class)))
                .thenReturn(List.of(placed1, placed2));

        // Side A won — the counterfactual win
        when(matchRepo.findByPlayersAndDate(eq(10L), eq(20L), eq(LocalDate.parse("2026-05-23"))))
                .thenReturn(Optional.of(match(10L, 20L, 10L, LocalDate.parse("2026-05-23"))));

        CounterfactualSkippedPickAnalysisService svc = new CounterfactualSkippedPickAnalysisService(
                decisionRepo, matchRepo, FIXED_CLOCK);

        CounterfactualSkippedReportDto report = svc.analyze(7);

        // Fallback stake should be avg of placed stakes: (60+40)/2 = 50.
        // Side A won at 3.00 decimal → profit = 50 * (3.00-1) = 100.00
        assertEquals(1, report.decided());
        assertEquals(1, report.wins());
        assertEquals(100.0, report.counterfactualPnL(), 0.01);
        assertEquals(50.0, report.byReason().get(0).totalProposedStake(), 0.01);
    }

    @Test
    void fallsBackToDefaultStakeWhenNoPlacedBetsExist() {
        // Empty PLACED list → default to $25 (paper-bankroll-sized).
        PaperTradeDecisionSampleRepository decisionRepo = mock(PaperTradeDecisionSampleRepository.class);
        MatchRepository matchRepo = mock(MatchRepository.class);
        PaperTradeDecisionSample skipped = sample(
                1L, 10L, "A", 20L, "B", 10L, "A",
                0.60, 0.50, 0.10, 100, null, // null stake
                "DUPLICATE_OPEN_EVENT",
                LocalDateTime.parse("2026-05-23T10:00:00"));
        when(decisionRepo.findByDecisionStatusAndCreatedAtAfterOrderByCreatedAtAsc(eq("SKIPPED"), any(LocalDateTime.class)))
                .thenReturn(List.of(skipped));
        when(decisionRepo.findByDecisionStatusAndCreatedAtAfterOrderByCreatedAtAsc(eq("PLACED"), any(LocalDateTime.class)))
                .thenReturn(List.of()); // no placed bets
        when(matchRepo.findByPlayersAndDate(eq(10L), eq(20L), eq(LocalDate.parse("2026-05-23"))))
                .thenReturn(Optional.of(match(10L, 20L, 20L, LocalDate.parse("2026-05-23")))); // B won → A lost

        CounterfactualSkippedPickAnalysisService svc = new CounterfactualSkippedPickAnalysisService(
                decisionRepo, matchRepo, FIXED_CLOCK);
        CounterfactualSkippedReportDto report = svc.analyze(7);

        assertEquals(1, report.losses());
        // Default $25 lost → -$25
        assertEquals(-25.0, report.counterfactualPnL(), 0.01);
    }

    @Test
    void clampsLookbackDaysIntoSensibleRange() {
        PaperTradeDecisionSampleRepository decisionRepo = mock(PaperTradeDecisionSampleRepository.class);
        MatchRepository matchRepo = mock(MatchRepository.class);
        when(decisionRepo.findByDecisionStatusAndCreatedAtAfterOrderByCreatedAtAsc(
                anyString(), any(LocalDateTime.class)))
                .thenReturn(List.of());

        CounterfactualSkippedPickAnalysisService svc = new CounterfactualSkippedPickAnalysisService(
                decisionRepo, matchRepo, FIXED_CLOCK);

        // 0 or negative -> 1 day
        assertEquals(1, svc.analyze(0).lookbackDays());
        assertEquals(1, svc.analyze(-5).lookbackDays());
        // Huge value -> capped at 90
        assertEquals(90, svc.analyze(9_999).lookbackDays());
        // Normal value passes through
        assertEquals(14, svc.analyze(14).lookbackDays());
    }

    private static PaperTradeDecisionSample sample(Long id,
                                                   Long p1Id, String p1Name,
                                                   Long p2Id, String p2Name,
                                                   Long sideId, String sideName,
                                                   Double modelProb, Double impliedProb, Double edge,
                                                   Integer american, Double stake,
                                                   String reason, LocalDateTime createdAt) {
        PaperTradeDecisionSample s = new PaperTradeDecisionSample();
        // id is @GeneratedValue (no setter); ignore for in-memory tests.
        s.setSessionId(1L);
        s.setSource("HARD_ROCK");
        s.setStrategy("CONSERVATIVE");
        s.setModelVersion("ENSEMBLE");
        s.setPlayer1Id(p1Id); s.setPlayer1Name(p1Name);
        s.setPlayer2Id(p2Id); s.setPlayer2Name(p2Name);
        s.setSidePlayerId(sideId); s.setSideName(sideName);
        s.setModelProbability(modelProb);
        s.setImpliedProbability(impliedProb);
        s.setSuggestedEdge(edge);
        s.setAmericanOdds(american);
        s.setProposedStake(stake);
        s.setCappedStake(stake);
        s.setDecisionStatus("SKIPPED");
        s.setDecisionReason(reason);
        s.setCreatedAt(createdAt);
        s.setRecommended(true);
        s.setFallbackPick(false);
        s.setLive(false);
        return s;
    }

    private static Match match(long p1Id, long p2Id, long winnerId, LocalDate date) {
        Match m = new Match();
        Player p1 = new Player(); p1.setId(p1Id);
        Player p2 = new Player(); p2.setId(p2Id);
        m.setPlayer1(p1);
        m.setPlayer2(p2);
        m.setWinnerPlayerId(winnerId);
        m.setDate(date);
        return m;
    }
}
