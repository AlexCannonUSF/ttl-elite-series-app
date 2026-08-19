package com.ttl.tabletennis.settlement;

import com.ttl.tabletennis.scrape.SourceId;
import com.ttl.tabletennis.settlement.observation.LiveObservation;
import com.ttl.tabletennis.settlement.observation.MatchPhase;
import com.ttl.tabletennis.settlement.observation.MirrorObservation;
import com.ttl.tabletennis.settlement.observation.ScoreState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoreEvidenceAnalyzerTests {

    @Test
    void commandingButUnfinishedScoreNeverBecomesWinnerEvidence() {
        SettlementEvidence evidence = evidence(
                List.of(live(0.92, MatchPhase.LIVE_LATE, new ScoreState(2, 1, 10, 5, ""), false)),
                List.of()
        );

        ScoreEvidenceAssessment assessment = ScoreEvidenceAnalyzer.assess(evidence);

        assertEquals(ScoreEvidenceAssessment.Quality.PARTIAL, assessment.quality());
        assertEquals(ScoreEvidenceAssessment.Finality.LIVE_PROGRESS, assessment.finality());
        assertEquals(null, assessment.inferredWinnerPlayerId());
        assertFalse(assessment.decisionGrade());
    }

    @Test
    void identityBoundTargetedCompletionIsDecisionGradeWithOneSource() {
        SettlementEvidence evidence = evidence(
                List.of(live(0.98, MatchPhase.FINISHED, new ScoreState(2, 1, 10, 9, ""), true)),
                List.of()
        );

        ScoreEvidenceAssessment assessment = ScoreEvidenceAnalyzer.assess(evidence);

        assertEquals(ScoreEvidenceAssessment.Quality.DECISION_GRADE, assessment.quality());
        assertEquals(ScoreEvidenceAssessment.Finality.COMPLETION_SIGNAL, assessment.finality());
        assertEquals(10L, assessment.inferredWinnerPlayerId());
        assertEquals("2-1 (10-9)", assessment.latestScore());
        assertTrue(assessment.decisionGrade());
    }

    @Test
    void twoIndependentFeedsPromoteEffectiveFinalScore() {
        SettlementEvidence evidence = evidence(
                List.of(live(0.94, MatchPhase.LIVE_LATE, new ScoreState(2, 1, 11, 9, ""), false)),
                List.of(new MirrorObservation(
                        SourceId.SOFASCORE,
                        Instant.parse("2026-07-29T20:00:02Z"),
                        0.92,
                        MatchPhase.LIVE_LATE,
                        new ScoreState(2, 1, 11, 9, ""),
                        "mirror",
                        false,
                        "mirror-event"
                ))
        );

        ScoreEvidenceAssessment assessment = ScoreEvidenceAnalyzer.assess(evidence);

        assertEquals(ScoreEvidenceAssessment.Quality.DECISION_GRADE, assessment.quality());
        assertEquals(ScoreEvidenceAssessment.Finality.EFFECTIVE_FINAL_SCORE, assessment.finality());
        assertEquals(2, assessment.agreeingSourceCount());
        assertEquals(10L, assessment.inferredWinnerPlayerId());
    }

    @Test
    void conflictingFinalScoresAreVisibleButBlocked() {
        SettlementEvidence evidence = evidence(
                List.of(live(0.98, MatchPhase.FINISHED, new ScoreState(3, 1, 0, 0, ""), true)),
                List.of(new MirrorObservation(
                        SourceId.SOFASCORE,
                        Instant.parse("2026-07-29T20:00:02Z"),
                        0.96,
                        MatchPhase.FINISHED,
                        new ScoreState(1, 3, 0, 0, ""),
                        "mirror",
                        true,
                        "mirror-event"
                ))
        );

        ScoreEvidenceAssessment assessment = ScoreEvidenceAnalyzer.assess(evidence);

        assertTrue(assessment.contradictory());
        assertFalse(assessment.decisionGrade());
        assertEquals(ScoreEvidenceAssessment.Quality.PARTIAL, assessment.quality());
    }

    private SettlementEvidence evidence(List<LiveObservation> live,
                                        List<MirrorObservation> mirrors) {
        return new SettlementEvidence(
                99L,
                new TrackedEventId("event-99"),
                new IdentityLock(
                        10L,
                        20L,
                        Instant.parse("2026-07-29T19:30:00Z"),
                        Duration.ofMinutes(90),
                        "booker-99",
                        "market-99"
                ),
                live,
                mirrors,
                List.of(),
                List.of(),
                List.of(),
                CoverageState.FULL,
                List.of(),
                0.0,
                0.95,
                Instant.parse("2026-07-29T20:00:05Z")
        );
    }

    private LiveObservation live(double confidence,
                                 MatchPhase phase,
                                 ScoreState score,
                                 boolean completion) {
        return new LiveObservation(
                SourceId.HR_TGT,
                Instant.parse("2026-07-29T20:00:01Z"),
                confidence,
                phase,
                score,
                "targeted",
                completion,
                "booker-99",
                "market-99",
                !completion,
                completion
        );
    }
}
