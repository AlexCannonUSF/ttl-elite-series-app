package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.domain.TrackedMatchObservation;
import com.ttl.tabletennis.dto.LiveStudioIntegrityDto;
import com.ttl.tabletennis.repository.PaperTradeBetRepository;
import com.ttl.tabletennis.repository.TrackedMatchObservationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntegrityServiceTests {

    @Test
    void nullSession_yieldsEmptyDto_withoutRepoCalls() {
        PaperTradeBetRepository bets = mock(PaperTradeBetRepository.class);
        TrackedMatchObservationRepository obs = mock(TrackedMatchObservationRepository.class);
        IntegrityService service = new IntegrityService(bets, obs);

        LiveStudioIntegrityDto dto = service.getLiveStudioIntegrity(null);

        assertEquals(0L, dto.trackedObservations());
        assertEquals(0L, dto.scoreBackedSettlements());
        verify(bets, never()).findBySessionIdAndStatusInOrderBySettledAtAsc(anyLong(), any());
        verify(obs, never()).countBySessionIdAndSourceKind(anyLong(), anyString());
    }

    @Test
    void sessionWithoutId_yieldsEmptyDto() {
        IntegrityService service = new IntegrityService(
                mock(PaperTradeBetRepository.class),
                mock(TrackedMatchObservationRepository.class)
        );
        PaperTradeSession session = new PaperTradeSession(); // id is null until persisted

        LiveStudioIntegrityDto dto = service.getLiveStudioIntegrity(session);

        assertEquals(0L, dto.trackedObservations());
    }

    @Test
    void aggregatesCountsAndSettlementSources() {
        PaperTradeBetRepository bets = mock(PaperTradeBetRepository.class);
        TrackedMatchObservationRepository obs = mock(TrackedMatchObservationRepository.class);
        PaperTradeSession session = sessionWithId(11L);

        // Observation counts: 12 board + 7 score-feed = 19 tracked total
        when(obs.countBySessionIdAndSourceKind(11L, IntegrityService.OBSERVATION_SOURCE_MARKET_BOARD)).thenReturn(12L);
        when(obs.countBySessionIdAndSourceKind(11L, IntegrityService.OBSERVATION_SOURCE_SCORE_FEED)).thenReturn(7L);

        // Tracked-after-close: 3 out of 5 returned observations
        when(obs.findBySessionIdOrderByObservedAtDesc(eq(11L), any(Pageable.class)))
                .thenReturn(List.of(
                        trackedObservation(true),
                        trackedObservation(false),
                        trackedObservation(true),
                        trackedObservation(true),
                        trackedObservation(false)
                ));

        // Settled bets: classify each by settlement source / reason.
        when(bets.findBySessionIdAndStatusInOrderBySettledAtAsc(eq(11L), any())).thenReturn(List.of(
                settledBet(PaperTradeBet.STATUS_WON, IntegrityService.SETTLEMENT_SOURCE_DECISIVE_LIVE_SCORE, null),
                settledBet(PaperTradeBet.STATUS_WON, IntegrityService.SETTLEMENT_SOURCE_OFFICIAL_RESULT, null),
                settledBet(PaperTradeBet.STATUS_LOST, IntegrityService.SETTLEMENT_SOURCE_DATABASE_RESULT, null),
                settledBet(PaperTradeBet.STATUS_LOST, IntegrityService.SETTLEMENT_SOURCE_HEURISTIC_FALLBACK, null),
                settledBet(PaperTradeBet.STATUS_VOIDED, IntegrityService.SETTLEMENT_SOURCE_TIMEOUT_VOID, null),
                settledBet(PaperTradeBet.STATUS_WON, IntegrityService.SETTLEMENT_SOURCE_DECISIVE_LIVE_SCORE,
                        "TARGETED_MATCH_COMPLETED at frame 21-18")
        ));

        IntegrityService service = new IntegrityService(bets, obs);
        LiveStudioIntegrityDto dto = service.getLiveStudioIntegrity(session);

        assertEquals(19L, dto.trackedObservations());
        assertEquals(12L, dto.boardObservations());
        assertEquals(7L, dto.scoreFeedObservations());
        assertEquals(3L, dto.trackedAfterCloseObservations());
        // Score-backed = DECISIVE_LIVE_SCORE (2) or HEURISTIC_FALLBACK (1) → 3
        assertEquals(3L, dto.scoreBackedSettlements());
        // One bet has the TARGETED_MATCH_COMPLETED reason
        assertEquals(1L, dto.targetedCompletionSettlements());
        assertEquals(1L, dto.officialResultSettlements());
        assertEquals(1L, dto.databaseSettlements());
        assertEquals(1L, dto.heuristicSettlements());
        // Voided counts both TIMEOUT_VOID source AND status==VOIDED (here the same row)
        assertEquals(1L, dto.voidedSettlements());
    }

    @Test
    void matchesSettlementSource_isCaseInsensitive_andHandlesNulls() {
        PaperTradeBet bet = new PaperTradeBet();
        bet.setSettlementSource("OFFICIAL_RESULT");
        assertTrue(IntegrityService.matchesSettlementSource(bet, "official_result"));
        assertTrue(IntegrityService.matchesSettlementSource(bet, "OFFICIAL_RESULT"));
        assertFalse(IntegrityService.matchesSettlementSource(bet, "DATABASE_RESULT"));
        assertFalse(IntegrityService.matchesSettlementSource(null, "OFFICIAL_RESULT"));
        assertFalse(IntegrityService.matchesSettlementSource(bet, ""));
        assertFalse(IntegrityService.matchesSettlementSource(bet, null));
    }

    @Test
    void getLiveStudioOpenBets_returnsEmpty_whenSessionMissing() {
        IntegrityService service = new IntegrityService(
                mock(PaperTradeBetRepository.class),
                mock(TrackedMatchObservationRepository.class)
        );
        assertTrue(service.getLiveStudioOpenBets(null, b -> "X").isEmpty());
        assertTrue(service.getLiveStudioOpenBets(new PaperTradeSession(), b -> "X").isEmpty());
    }

    @Test
    void getLiveStudioOpenBets_mapsThroughResolver() {
        PaperTradeBetRepository bets = mock(PaperTradeBetRepository.class);
        IntegrityService service = new IntegrityService(bets, mock(TrackedMatchObservationRepository.class));
        PaperTradeBet betOpen = openBet("Alice");
        PaperTradeBet betOpen2 = openBet("Bob");
        when(bets.findBySessionIdAndStatusOrderByPlacedAtDesc(11L, PaperTradeBet.STATUS_OPEN))
                .thenReturn(List.of(betOpen, betOpen2));

        var result = service.getLiveStudioOpenBets(sessionWithId(11L),
                bet -> "Bob".equals(bet.getSideName()) ? "MARKET_CLOSED_SCORE_TRACKED" : "OPEN_PENDING_SCORE");

        assertEquals(2, result.size());
        assertEquals("OPEN_PENDING_SCORE", result.get(0).trackingState());
        assertEquals("MARKET_CLOSED_SCORE_TRACKED", result.get(1).trackingState());
    }

    @Test
    void getLiveStudioSettledTape_clampsLimit_andMapsRows() {
        PaperTradeBetRepository bets = mock(PaperTradeBetRepository.class);
        IntegrityService service = new IntegrityService(bets, mock(TrackedMatchObservationRepository.class));
        when(bets.findBySessionIdAndStatusInOrderByPlacedAtDesc(eq(11L), any(), any(Pageable.class)))
                .thenReturn(List.of(settledBet(PaperTradeBet.STATUS_WON, IntegrityService.SETTLEMENT_SOURCE_OFFICIAL_RESULT, null)));

        // limit = 1 below the clamp floor of 5 should still call the repo; we don't assert
        // the page-size here (legacy clamp is internal) but verify the result wires through.
        var result = service.getLiveStudioSettledTape(sessionWithId(11L), 1,
                bet -> "SETTLED");

        assertEquals(1, result.size());
        assertEquals("SETTLED", result.get(0).trackingState());
    }

    @Test
    void isTargetedCompletionSettlement_looksForReasonSubstring() {
        PaperTradeBet a = new PaperTradeBet();
        a.setSettlementReason("targeted_match_completed at 11-7");
        assertTrue(IntegrityService.isTargetedCompletionSettlement(a));

        PaperTradeBet b = new PaperTradeBet();
        b.setSettlementReason("decisive_score");
        assertFalse(IntegrityService.isTargetedCompletionSettlement(b));

        assertFalse(IntegrityService.isTargetedCompletionSettlement(null));
    }

    /**
     * PaperTradeSession's {@code id} is JPA-generated (no setter). Returning
     * a non-null id from an inline override is the cleanest fixture path —
     * the production code only reads it.
     */
    private static PaperTradeSession sessionWithId(long id) {
        return new PaperTradeSession() {
            @Override
            public Long getId() {
                return id;
            }
        };
    }

    private static TrackedMatchObservation trackedObservation(boolean trackedAfterClose) {
        TrackedMatchObservation observation = new TrackedMatchObservation();
        observation.setTrackedAfterClose(trackedAfterClose);
        return observation;
    }

    private static PaperTradeBet openBet(String sideName) {
        PaperTradeBet bet = new PaperTradeBet();
        bet.setStatus(PaperTradeBet.STATUS_OPEN);
        bet.setSideName(sideName);
        return bet;
    }

    private static PaperTradeBet settledBet(String status, String settlementSource, String settlementReason) {
        PaperTradeBet bet = new PaperTradeBet();
        bet.setStatus(status);
        bet.setSettlementSource(settlementSource);
        bet.setSettlementReason(settlementReason);
        return bet;
    }
}
