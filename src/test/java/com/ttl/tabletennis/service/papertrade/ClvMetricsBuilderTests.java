package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.OddsSnapshot;
import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.dto.PaperTradingSessionDto;
import com.ttl.tabletennis.repository.OddsSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClvMetricsBuilderTests {

    private static final double EPS = 1e-9;

    @Test
    void returnsEmptyDto_whenRepositoryIsNull() {
        ClvMetricsBuilder builder = new ClvMetricsBuilder(null);

        PaperTradingSessionDto.ClvMetricsDto result = builder.buildClvMetrics(List.of(bet("evt-1", 1L)));

        assertEquals(0, result.betsInWindow());
        assertEquals(0, result.betsWithClosingSnapshot());
        assertEquals(0.0, result.coverageRatio(), EPS);
        assertEquals(0.0, result.avgClvPct(), EPS);
        assertNull(result.lastClosingSnapshotAt());
    }

    @Test
    void returnsEmptyDto_whenRecentRowsIsNull() {
        OddsSnapshotRepository repo = mock(OddsSnapshotRepository.class);
        ClvMetricsBuilder builder = new ClvMetricsBuilder(repo);

        PaperTradingSessionDto.ClvMetricsDto result = builder.buildClvMetrics(null);

        assertEquals(0, result.betsInWindow());
        verify(repo, never()).findClosingCandidates(any(), any(), any(), any(), any());
    }

    @Test
    void skipsBets_outsideSevenDayWindow() {
        OddsSnapshotRepository repo = mock(OddsSnapshotRepository.class);
        ClvMetricsBuilder builder = new ClvMetricsBuilder(repo);
        PaperTradeBet old = bet("evt-1", 1L);
        old.setPlacedAt(LocalDateTime.now().minusDays(30));

        PaperTradingSessionDto.ClvMetricsDto result = builder.buildClvMetrics(List.of(old));

        assertEquals(0, result.betsInWindow());
        verify(repo, never()).findClosingCandidates(any(), any(), any(), any(), any());
    }

    @Test
    void skipsBets_withMissingEventIdOrSide() {
        OddsSnapshotRepository repo = mock(OddsSnapshotRepository.class);
        ClvMetricsBuilder builder = new ClvMetricsBuilder(repo);
        PaperTradeBet missingId = bet("", 1L);
        PaperTradeBet missingSide = bet("evt-2", null);
        // missingSide also lacks player2Id, so name fallback can't match either.
        missingSide.setPlayer1Id(null);
        missingSide.setPlayer2Id(null);
        missingSide.setPlayer1Name(null);
        missingSide.setPlayer2Name(null);
        missingSide.setSideName(null);

        PaperTradingSessionDto.ClvMetricsDto result = builder.buildClvMetrics(List.of(missingId, missingSide));

        assertEquals(0, result.betsInWindow());
        verify(repo, never()).findClosingCandidates(any(), any(), any(), any(), any());
    }

    @Test
    void aggregatesClvAcrossMatchedBets() {
        OddsSnapshotRepository repo = mock(OddsSnapshotRepository.class);
        ClvMetricsBuilder builder = new ClvMetricsBuilder(repo);

        // Bet A: placed at 0.40 implied, closing at 0.50 implied → CLV = +0.10
        PaperTradeBet betA = bet("evt-A", 1L);
        betA.setImpliedProbability(0.40);
        OddsSnapshot snapA = snapshot(0.50, LocalDateTime.parse("2026-05-01T10:00:00"));
        when(repo.findClosingCandidates(eq("evt-A"), eq("P1"),
                any(LocalDateTime.class), any(LocalDateTime.class), ArgumentMatchers.any(Pageable.class)))
                .thenReturn(List.of(snapA));

        // Bet B: placed at 0.60 implied, closing at 0.55 implied → CLV = −0.05
        PaperTradeBet betB = bet("evt-B", 2L);
        betB.setImpliedProbability(0.60);
        betB.setSidePlayerId(2L);
        OddsSnapshot snapB = snapshot(0.55, LocalDateTime.parse("2026-05-01T12:00:00"));
        when(repo.findClosingCandidates(eq("evt-B"), eq("P2"),
                any(LocalDateTime.class), any(LocalDateTime.class), ArgumentMatchers.any(Pageable.class)))
                .thenReturn(List.of(snapB));

        PaperTradingSessionDto.ClvMetricsDto result = builder.buildClvMetrics(List.of(betA, betB));

        assertEquals(2, result.betsInWindow());
        assertEquals(2, result.betsWithClosingSnapshot());
        assertEquals(1.0, result.coverageRatio(), EPS);
        // avgClv = (+0.10 + −0.05) / 2 = 0.025 → 2.5 pct
        assertEquals(2.5, result.avgClvPct(), 1e-6);
        // avgPlaced = 0.50 → 50 pct
        assertEquals(50.0, result.avgPlacedImpliedPct(), 1e-6);
        // avgClosing = 0.525 → 52.5 pct
        assertEquals(52.5, result.avgClosingImpliedPct(), 1e-6);
        // lastClosingSnapshotAt = the later one
        assertEquals(LocalDateTime.parse("2026-05-01T12:00:00"), result.lastClosingSnapshotAt());
    }

    @Test
    void countsInWindowButNotMatched_whenRepositoryReturnsEmpty() {
        OddsSnapshotRepository repo = mock(OddsSnapshotRepository.class);
        ClvMetricsBuilder builder = new ClvMetricsBuilder(repo);
        PaperTradeBet bet = bet("evt-X", 1L);
        when(repo.findClosingCandidates(any(), any(), any(), any(), any())).thenReturn(List.of());

        PaperTradingSessionDto.ClvMetricsDto result = builder.buildClvMetrics(List.of(bet));

        assertEquals(1, result.betsInWindow());
        assertEquals(0, result.betsWithClosingSnapshot());
        assertEquals(0.0, result.coverageRatio(), EPS);
    }

    @Test
    void treatsRepositoryException_asNoMatch() {
        OddsSnapshotRepository repo = mock(OddsSnapshotRepository.class);
        ClvMetricsBuilder builder = new ClvMetricsBuilder(repo);
        PaperTradeBet bet = bet("evt-X", 1L);
        when(repo.findClosingCandidates(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("boom"));

        PaperTradingSessionDto.ClvMetricsDto result = builder.buildClvMetrics(List.of(bet));

        assertEquals(1, result.betsInWindow(), "betsInWindow still counts the candidate row");
        assertEquals(0, result.betsWithClosingSnapshot(), "but no closing snapshot was usable");
    }

    @Test
    void snapshotSide_fallsBackToNameWhenIdsMissing() {
        PaperTradeBet bet = bet("evt-N", null);
        bet.setSidePlayerId(null);
        bet.setSideName("Alice");
        bet.setPlayer1Name(" alice ");
        bet.setPlayer2Name("Bob");

        assertEquals("P1", ClvMetricsBuilder.snapshotSide(bet));
    }

    @Test
    void firstNonBlank_returnsTrimmedValueOrNull() {
        assertNull(ClvMetricsBuilder.firstNonBlank((String[]) null));
        assertNull(ClvMetricsBuilder.firstNonBlank("", "  ", null));
        assertEquals("hit", ClvMetricsBuilder.firstNonBlank(null, "", "  hit  ", "ignored"));
    }

    private static PaperTradeBet bet(String externalEventId, Long sidePlayerId) {
        PaperTradeBet bet = new PaperTradeBet();
        bet.setExternalEventId(externalEventId);
        bet.setLockedExternalEventId(null);
        bet.setPlacedAt(LocalDateTime.now().minusHours(1));
        bet.setSettledAt(null);
        bet.setImpliedProbability(0.50);
        bet.setSidePlayerId(sidePlayerId);
        bet.setPlayer1Id(1L);
        bet.setPlayer2Id(2L);
        bet.setPlayer1Name("P1");
        bet.setPlayer2Name("P2");
        bet.setSideName(sidePlayerId == null ? null : (sidePlayerId == 1L ? "P1" : "P2"));
        return bet;
    }

    private static OddsSnapshot snapshot(double impliedProb, LocalDateTime observedAt) {
        OddsSnapshot snap = new OddsSnapshot();
        snap.setImpliedProb(impliedProb);
        snap.setObservedAt(observedAt);
        snap.setMarketState("CLOSED");
        return snap;
    }

}
