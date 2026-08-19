package com.ttl.tabletennis.settlement;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.TrackedMatchObservation;
import com.ttl.tabletennis.repository.TrackedMatchObservationRepository;
import com.ttl.tabletennis.scrape.SourceId;
import com.ttl.tabletennis.settlement.observation.LiveObservation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SettlementEvidenceBuilderTests {

    @Test
    void buildsEvidenceFromTrackedTimelineAndResolvedBetState() {
        TrackedMatchObservationRepository repository = mock(TrackedMatchObservationRepository.class);
        SettlementEvidenceBuilder builder = new SettlementEvidenceBuilder(repository, new AmbiguityScorer());

        PaperTradeBet bet = new PaperTradeBet();
        setId(bet, 55L);
        bet.setEventKey("match:55");
        bet.setDedupeKey("dedupe:55");
        bet.setExternalEventId("booker-55");
        bet.setLockedExternalEventId("booker-55");
        bet.setLockedSourceFeedEventId("market-55");
        bet.setPlayer1Id(10L);
        bet.setPlayer2Id(20L);
        bet.setPlacedAt(LocalDateTime.of(2026, 4, 19, 12, 0));
        bet.setStatus(PaperTradeBet.STATUS_WON);
        bet.setWinnerPlayerId(10L);
        bet.setSettlementReason("SETTLED_FROM_OFFICIAL_RESULT");
        bet.setSettlementSource("OFFICIAL_RESULT");
        bet.setSettledAt(LocalDateTime.of(2026, 4, 19, 12, 45));

        TrackedMatchObservation observation = new TrackedMatchObservation();
        observation.setBetId(55L);
        observation.setSessionId(7L);
        observation.setEventKey("match:55");
        observation.setSource("betradar_score");
        observation.setSourceKind("SCORE_FEED");
        observation.setSourceConfidence(0.93);
        observation.setSourceFeedEventId("booker-55");
        observation.setLive(true);
        observation.setTrackedAfterClose(true);
        observation.setDisplayed(false);
        observation.setResulted(false);
        observation.setMatchCompleted(false);
        observation.setLiveScore("2-2 (8-10)");
        observation.setMatchPhase("LIVE_LATE");
        observation.setScoreDetail("11-9, 9-11, 8-11, 11-8");
        observation.setObservedAt(LocalDateTime.of(2026, 4, 19, 12, 40));

        when(repository.findByBetIdOrderByObservedAtAsc(55L)).thenReturn(List.of(observation));

        SettlementEvidence evidence = builder.buildForBet(bet).orElseThrow();

        assertEquals(55L, evidence.betId());
        assertEquals(CoverageState.FULL, evidence.coverageState());
        assertEquals(1, evidence.liveObservations().size());
        assertEquals(1, evidence.databaseCandidates().size());
        assertEquals(0, evidence.officialCandidates().size());
        assertFalse(evidence.hasContradictions());

        LiveObservation liveObservation = evidence.liveObservations().get(0);
        assertEquals(SourceId.HR_TGT, liveObservation.source());
        assertEquals("booker-55", liveObservation.bookerEventId());
        assertEquals("market-55", liveObservation.bookerMarketId());
        assertEquals(2, liveObservation.score().gamesP1());
        assertEquals(2, liveObservation.score().gamesP2());
        assertEquals(8, liveObservation.score().pointsP1());
        assertEquals(10, liveObservation.score().pointsP2());
        assertTrue(evidence.confidence() > 0.8);
        assertTrue(evidence.ambiguityScore() < 0.3);
    }

    @Test
    void promotesIdentityLockedTargetedCompletionConfidence() {
        TrackedMatchObservationRepository repository = mock(TrackedMatchObservationRepository.class);
        SettlementEvidenceBuilder builder = new SettlementEvidenceBuilder(repository, new AmbiguityScorer());

        PaperTradeBet bet = new PaperTradeBet();
        setId(bet, 56L);
        bet.setEventKey("match:56");
        bet.setExternalEventId("booker-56");
        bet.setLockedExternalEventId("booker-56");
        bet.setLockedSourceFeedEventId("booker-56");
        bet.setPlayer1Id(10L);
        bet.setPlayer2Id(20L);
        bet.setPlacedAt(LocalDateTime.of(2026, 7, 29, 12, 0));

        TrackedMatchObservation observation = new TrackedMatchObservation();
        observation.setBetId(56L);
        observation.setSessionId(7L);
        observation.setEventKey("match:56");
        // Regression: the production structured-score route contains the
        // word STREAM but is a targeted Hard Rock/Betradar feed, not CV.
        observation.setSource("HARD_ROCK_SCORE_STREAM:FLORIDA_ONLINE|event=booker-56");
        observation.setSourceKind("SCORE_FEED");
        observation.setSourceFeedCode("BETRADAR_UF");
        observation.setSourceConfidence(0.90);
        observation.setExternalEventId("booker-56");
        observation.setSourceFeedEventId("booker-56");
        observation.setPlayer1Id(10L);
        observation.setPlayer2Id(20L);
        observation.setDisplayed(false);
        observation.setResulted(true);
        observation.setMatchCompleted(false);
        observation.setLive(false);
        observation.setTrackedAfterClose(true);
        observation.setLiveScore("2-1 (10-9)");
        observation.setMatchPhase("FINISHED");
        observation.setObservedAt(LocalDateTime.of(2026, 7, 29, 12, 30));

        when(repository.findByBetIdOrderByObservedAtAsc(56L)).thenReturn(List.of(observation));

        SettlementEvidence evidence = builder.buildForBet(bet).orElseThrow();
        LiveObservation targeted = evidence.liveObservations().get(0);

        assertEquals(SourceId.HR_TGT, targeted.source());
        assertEquals(0.98, targeted.confidence(), 1e-9);
        assertTrue(targeted.completionSignal());
        assertEquals(
                ScoreEvidenceAssessment.Quality.DECISION_GRADE,
                ScoreEvidenceAnalyzer.assess(evidence).quality()
        );
    }

    private void setId(PaperTradeBet bet, Long id) {
        try {
            Field field = PaperTradeBet.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(bet, id);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("unable to set bet id for test", ex);
        }
    }
}
