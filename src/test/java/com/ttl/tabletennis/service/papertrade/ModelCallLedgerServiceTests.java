package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.PaperTradeModelCall;
import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.dto.LiveOddsRecommendationDto;
import com.ttl.tabletennis.dto.ModelCallScorecardDto;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.PaperTradeModelCallRepository;
import com.ttl.tabletennis.repository.PaperTradeSessionRepository;
import com.ttl.tabletennis.repository.TrackedMatchObservationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelCallLedgerServiceTests {

    private final PaperTradeModelCallRepository callRepository = mock(PaperTradeModelCallRepository.class);
    private final PaperTradeSessionRepository sessionRepository = mock(PaperTradeSessionRepository.class);
    private final MatchRepository matchRepository = mock(MatchRepository.class);
    private final TrackedMatchObservationRepository observationRepository = mock(TrackedMatchObservationRepository.class);
    private final ModelCallLedgerService service =
            new ModelCallLedgerService(callRepository, sessionRepository, matchRepository, observationRepository);

    @Test
    void recordsMostLikelyWinnerInsteadOfValueBetSide() {
        LiveOddsRecommendationDto row = row(false, false, 0.39, 0.61, "Alpha One");
        when(callRepository.findBySessionIdAndEventKey(7L, "event-1")).thenReturn(Optional.empty());
        when(matchRepository.findMaxMatchId()).thenReturn(88L);

        service.recordCall(7L, "CONSERVATIVE", "ENSEMBLE", row,
                "event-1", "SKIPPED", "MODEL_EDGE_BELOW_THRESHOLD");

        ArgumentCaptor<PaperTradeModelCall> saved = ArgumentCaptor.forClass(PaperTradeModelCall.class);
        verify(callRepository).save(saved.capture());
        PaperTradeModelCall call = saved.getValue();
        assertEquals(2L, call.getPredictedWinnerPlayerId());
        assertEquals("Beta Two", call.getPredictedWinnerName());
        assertEquals(0.61, call.getModelProbability());
        assertEquals(-156, call.getModelFairAmericanOdds());
        assertEquals(-145, call.getHardRockAmericanOdds());
        assertEquals(88L, call.getMatchIdHighWatermark());
        assertFalse(call.isHasPaperPick());
    }

    @Test
    void liveScoreCannotOverwriteFrozenPregameCall() {
        PaperTradeModelCall frozen = call(0.62, 1L, PaperTradeModelCall.CAPTURE_PREMATCH_CLOSE);
        frozen.setDecisionReason("PREMATCH_PASS");
        when(callRepository.findBySessionIdAndEventKey(7L, "event-1")).thenReturn(Optional.of(frozen));

        LiveOddsRecommendationDto live = row(true, false, 0.28, 0.72, "Beta Two");
        service.recordCall(7L, "CONSERVATIVE", "ENSEMBLE", live,
                "event-1", "PLACED", "PLACED_PRIMARY");

        assertEquals(1L, frozen.getPredictedWinnerPlayerId());
        assertEquals(0.62, frozen.getModelProbability());
        assertEquals(PaperTradeModelCall.CAPTURE_PREMATCH_CLOSE, frozen.getCaptureType());
        assertEquals("PREMATCH_PASS", frozen.getDecisionReason());
        assertTrue(frozen.isHasPaperPick());
        verify(callRepository).save(frozen);
    }

    @Test
    void completedRowCannotCreateHindsightCall() {
        when(callRepository.findBySessionIdAndEventKey(7L, "event-1")).thenReturn(Optional.empty());

        service.recordCall(7L, "CONSERVATIVE", "ENSEMBLE",
                row(true, true, 0.20, 0.80, "Beta Two"),
                "event-1", "SKIPPED", "MATCH_FINISHED");

        verify(callRepository, never()).save(any());
        verify(matchRepository, never()).findMaxMatchId();
    }

    @Test
    void scorecardGradesAllCallsWithoutRequiringPaperBet() {
        PaperTradeSession session = mock(PaperTradeSession.class);
        when(session.getId()).thenReturn(7L);
        when(session.getLabel()).thenReturn("Long simulation");
        when(sessionRepository.findFirstByStatusOrderByIdDesc(PaperTradeSession.STATUS_ACTIVE))
                .thenReturn(Optional.of(session));

        PaperTradeModelCall call = call(0.62, 1L, PaperTradeModelCall.CAPTURE_PREMATCH_CLOSE);
        call.setSessionId(7L);
        call.setEventKey("event-1");
        call.setStartTimeIso("2026-08-08T14:00:00-04:00");
        call.setMatchIdHighWatermark(100L);
        call.setHasPaperPick(false);
        when(callRepository.findBySessionIdOrderByCapturedAtDesc(7L)).thenReturn(List.of(call));

        Player alpha = new Player("Alpha", "One");
        alpha.setId(1L);
        Player beta = new Player("Beta", "Two");
        beta.setId(2L);
        Match result = new Match();
        result.setId(101L);
        result.setDate(LocalDate.of(2026, 8, 8));
        result.setPlayer1(alpha);
        result.setPlayer2(beta);
        result.setWinnerPlayerId(1L);
        result.setResult("3:1");
        result.setComplete(true);
        when(matchRepository.findCompletedMatchesByPlayersOnDate(1L, 2L, LocalDate.of(2026, 8, 8)))
                .thenReturn(List.of(result));

        ModelCallScorecardDto scorecard = service.scorecard(40);

        assertEquals(1, scorecard.totalCalls());
        assertEquals(1, scorecard.settledCalls());
        assertEquals(1, scorecard.correct());
        assertEquals(0, scorecard.incorrect());
        assertEquals(100.0, scorecard.accuracyPct());
        assertEquals(100.0, scorecard.pregameAccuracyPct());
        assertEquals(0, scorecard.awaitingResult());
        assertEquals(0.1444, scorecard.brierScore());
        assertEquals("CORRECT", scorecard.recentResults().get(0).outcome());
        assertFalse(scorecard.recentResults().get(0).paperPickPlaced());
    }

    @Test
    void exactFiftyFiftyIsShownButExcludedFromAccuracy() {
        when(callRepository.findBySessionIdAndEventKey(7L, "event-1")).thenReturn(Optional.empty());
        when(matchRepository.findMaxMatchId()).thenReturn(0L);

        service.recordCall(7L, "CONSERVATIVE", "ENSEMBLE",
                row(false, false, 0.50, 0.50, null),
                "event-1", "SKIPPED", "NO_EDGE");

        ArgumentCaptor<PaperTradeModelCall> saved = ArgumentCaptor.forClass(PaperTradeModelCall.class);
        verify(callRepository).save(saved.capture());
        assertNull(saved.getValue().getPredictedWinnerPlayerId());
        assertNull(saved.getValue().getPredictedWinnerName());
        assertEquals(0.50, saved.getValue().getModelProbability());
    }

    private static PaperTradeModelCall call(double probability, long winnerId, String captureType) {
        PaperTradeModelCall call = new PaperTradeModelCall();
        call.setSessionId(7L);
        call.setEventKey("event-1");
        call.setEventName("Alpha One vs Beta Two");
        call.setCompetitionName("TTL Elite Series");
        call.setCaptureType(captureType);
        call.setCapturedAt(LocalDateTime.of(2026, 8, 8, 13, 55));
        call.setPlayer1Id(1L);
        call.setPlayer1Name("Alpha One");
        call.setPlayer2Id(2L);
        call.setPlayer2Name("Beta Two");
        call.setPredictedWinnerPlayerId(winnerId);
        call.setPredictedWinnerName(winnerId == 1L ? "Alpha One" : "Beta Two");
        call.setModelProbability(probability);
        call.setModelFairAmericanOdds(-163);
        call.setHardRockAmericanOdds(-145);
        call.setOpponentHardRockAmericanOdds(120);
        call.setHardRockNoVigProbability(0.57);
        call.setDecisionStatus("SKIPPED");
        call.setDecisionReason("MODEL_EDGE_BELOW_THRESHOLD");
        return call;
    }

    private static LiveOddsRecommendationDto row(boolean live,
                                                  boolean completed,
                                                  double player1Probability,
                                                  double player2Probability,
                                                  String suggestedSide) {
        return new LiveOddsRecommendationDto(
                "HARD_ROCK", "CONSERVATIVE", "ENSEMBLE",
                "Alpha One vs Beta Two", "TTL Elite Series",
                live, "2026-08-08T14:00:00-04:00", live ? "1:2" : null,
                completed ? "FINISHED" : (live ? "LIVE_MID" : "UPCOMING"),
                1L, "Alpha One", 2L, "Beta Two",
                2.20, 1.69, 120, -145,
                0.4545, 0.5917,
                player1Probability, player2Probability,
                player1Probability - 0.4545, player2Probability - 0.5917,
                156, -156,
                suggestedSide, 0.03, suggestedSide != null && suggestedSide.equals("Alpha One") ? 156 : -156,
                0.48, 0.74, false, "WATCH", "Pass", "Ratings", 0.2,
                0.7, 0.8, 0.6, 0.7,
                "event-1", "event-1|side", "SPORTSBOOK", 1.0, "hr-123",
                true, completed, completed, "HR_TGT", "sr:match:123", null
        );
    }
}
