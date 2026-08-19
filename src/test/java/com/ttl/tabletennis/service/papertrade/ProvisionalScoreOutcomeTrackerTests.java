package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.TrackedMatchObservation;
import com.ttl.tabletennis.repository.TrackedMatchObservationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProvisionalScoreOutcomeTrackerTests {

    @Test
    void currentScoreLeaderIsRecordedAsShadowEvidence() {
        ScoreWinnerResolver resolver = mock(ScoreWinnerResolver.class);
        TrackedMatchObservationRepository repository = mock(TrackedMatchObservationRepository.class);
        when(resolver.determineWinnerFromScore(anyString(), anyLong(), anyLong(), anyString(), anyBoolean()))
                .thenReturn(Optional.empty());
        when(resolver.determineWinnerFromConfidenceState(anyString(), anyLong(), anyLong(), anyString()))
                .thenReturn(Optional.empty());
        ProvisionalScoreOutcomeTracker tracker = new ProvisionalScoreOutcomeTracker(resolver, repository);
        TrackedMatchObservation observation = observation("2-1 (9-5)");

        tracker.annotate(observation);

        assertEquals(1L, observation.getProvisionalWinnerPlayerId());
        assertEquals(ProvisionalScoreOutcomeTracker.CURRENT_SCORE_LEADER,
                observation.getProvisionalOutcomeMethod());
        assertNotNull(observation.getProvisionalOutcomeConfidence());
    }

    @Test
    void terminalResolverTakesPriorityAndUsesNearCertainConfidence() {
        ScoreWinnerResolver resolver = mock(ScoreWinnerResolver.class);
        TrackedMatchObservationRepository repository = mock(TrackedMatchObservationRepository.class);
        when(resolver.determineWinnerFromScore(anyString(), anyLong(), anyLong(), anyString(), anyBoolean()))
                .thenReturn(Optional.of(2L));
        ProvisionalScoreOutcomeTracker tracker = new ProvisionalScoreOutcomeTracker(resolver, repository);

        ProvisionalScoreOutcomeTracker.Estimate estimate =
                tracker.estimate("1-3", "FINISHED", 1L, 2L).orElseThrow();

        assertEquals(2L, estimate.winnerPlayerId());
        assertEquals(ProvisionalScoreOutcomeTracker.TERMINAL_SCORE, estimate.method());
        assertEquals(0.99, estimate.confidence());
    }

    @Test
    void trustedSettlementLabelsPriorGuessWithoutSettlingFromTheGuess() {
        ScoreWinnerResolver resolver = mock(ScoreWinnerResolver.class);
        TrackedMatchObservationRepository repository = mock(TrackedMatchObservationRepository.class);
        ProvisionalScoreOutcomeTracker tracker = new ProvisionalScoreOutcomeTracker(resolver, repository);
        TrackedMatchObservation correct = observation("2-0");
        correct.setProvisionalWinnerPlayerId(1L);
        TrackedMatchObservation incorrect = observation("0-2");
        incorrect.setProvisionalWinnerPlayerId(2L);

        PaperTradeBet bet = new PaperTradeBet();
        ReflectionTestUtils.setField(bet, "id", 77L);
        bet.setWinnerPlayerId(1L);
        bet.setSettledAt(LocalDateTime.parse("2026-07-29T16:00:00"));
        when(repository.findByBetIdOrderByObservedAtAsc(77L)).thenReturn(List.of(correct, incorrect));

        tracker.resolve(bet);

        assertEquals(Boolean.TRUE, correct.getProvisionalCorrect());
        assertFalse(incorrect.getProvisionalCorrect());
        assertEquals(1L, correct.getResolvedWinnerPlayerId());
        assertNotNull(correct.getProvisionalResolvedAt());
        verify(repository).saveAll(List.of(correct, incorrect));
    }

    private TrackedMatchObservation observation(String score) {
        TrackedMatchObservation observation = new TrackedMatchObservation();
        observation.setPlayer1Id(1L);
        observation.setPlayer2Id(2L);
        observation.setLiveScore(score);
        observation.setMatchPhase("LIVE_MID");
        return observation;
    }
}
