package com.ttl.tabletennis.prediction.staking;

import com.ttl.tabletennis.domain.OddsSnapshot;
import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.repository.OddsSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClosingLineLookupServiceTests {

    private final OddsSnapshotRepository repository = mock(OddsSnapshotRepository.class);
    private final ClosingLineLookupService service = new ClosingLineLookupService(repository);

    @Test
    void mapsPlayer1SideToTopSnapshotKey() {
        PaperTradeBet bet = betPlayer1Side();
        ArgumentCaptor<String> sideCaptor = ArgumentCaptor.forClass(String.class);
        when(repository.findClosingCandidates(
                anyString(), sideCaptor.capture(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(snapshot(2.10, "CLOSED")));

        Optional<ClosingLineLookupService.ClosingLine> line = service.findFor(bet);

        assertTrue(line.isPresent());
        assertEquals(ClosingLineLookupService.SIDE_TOP, sideCaptor.getValue());
        assertEquals(2.10, line.get().decimalOdds(), 1e-9);
        verify(repository).findClosingCandidates(
                eq("event-7"), eq(ClosingLineLookupService.SIDE_TOP),
                any(), any(), any(Pageable.class));
    }

    @Test
    void mapsPlayer2SideToBotSnapshotKey() {
        PaperTradeBet bet = betPlayer1Side();
        bet.setSidePlayerId(bet.getPlayer2Id());
        when(repository.findClosingCandidates(
                anyString(), eq(ClosingLineLookupService.SIDE_BOT),
                any(), any(), any(Pageable.class))).thenReturn(List.of(snapshot(1.95, "CLOSED")));

        Optional<ClosingLineLookupService.ClosingLine> line = service.findFor(bet);

        assertTrue(line.isPresent());
        assertEquals(1.95, line.get().decimalOdds(), 1e-9);
    }

    @Test
    void returnsEmptyWhenNoSnapshotsFound() {
        when(repository.findClosingCandidates(
                anyString(), anyString(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of());

        assertTrue(service.findFor(betPlayer1Side()).isEmpty());
    }

    @Test
    void returnsEmptyWhenBetMissingIdentifiers() {
        PaperTradeBet bet = new PaperTradeBet();
        // no external event id, no side player → can't resolve
        assertTrue(service.findFor(bet).isEmpty());
    }

    @Test
    void returnsEmptyWhenSnapshotPriceIsImplausible() {
        when(repository.findClosingCandidates(
                anyString(), anyString(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(snapshot(0.5, "CLOSED")));

        assertTrue(service.findFor(betPlayer1Side()).isEmpty());
    }

    @Test
    void swallowsRepositoryExceptions() {
        when(repository.findClosingCandidates(
                anyString(), anyString(), any(), any(), any(Pageable.class)))
                .thenThrow(new RuntimeException("boom"));

        assertTrue(service.findFor(betPlayer1Side()).isEmpty());
    }

    private static PaperTradeBet betPlayer1Side() {
        PaperTradeBet bet = new PaperTradeBet();
        bet.setLockedExternalEventId("event-7");
        bet.setPlayer1Id(101L);
        bet.setPlayer2Id(202L);
        bet.setSidePlayerId(101L);
        bet.setPlacedAt(LocalDateTime.of(2026, 5, 19, 12, 0));
        bet.setSettledAt(LocalDateTime.of(2026, 5, 19, 13, 30));
        return bet;
    }

    private static OddsSnapshot snapshot(double decimal, String marketState) {
        OddsSnapshot s = new OddsSnapshot();
        s.setPriceDecimal(decimal);
        s.setMarketState(marketState);
        s.setObservedAt(LocalDateTime.of(2026, 5, 19, 13, 0));
        s.setSourceId("HRG");
        return s;
    }
}
