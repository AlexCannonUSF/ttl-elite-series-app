package com.ttl.tabletennis.settlement;

import com.ttl.tabletennis.scrape.SourceId;
import com.ttl.tabletennis.settlement.observation.LiveObservation;
import com.ttl.tabletennis.settlement.observation.MatchPhase;
import com.ttl.tabletennis.settlement.observation.ScoreState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultSettlementEngineTests {

    private final DefaultSettlementEngine engine = new DefaultSettlementEngine(
            new AmbiguityScorer(),
            new ContradictionGuard()
    );

    @Test
    void settlesFromDatabaseConfirmationWithSingleT4Source() {
        SettlementEvidence evidence = new SettlementEvidence(
                1L,
                new TrackedEventId("tracked-1"),
                identityLock(Instant.parse("2026-04-19T12:00:00Z")),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new DatabaseCandidate(
                        101L,
                        Instant.parse("2026-04-19T12:30:00Z"),
                        LocalDate.of(2026, 4, 19),
                        10L,
                        20L,
                        10L,
                        "booker-1",
                        0.95,
                        true,
                        "raw-db"
                )),
                CoverageState.PARTIAL,
                List.of(),
                0.0,
                0.95,
                Instant.parse("2026-04-19T12:30:30Z")
        );

        Decision decision = engine.decide(evidence, SettlementPolicy.defaults());

        Settle settle = assertInstanceOf(Settle.class, decision);
        assertEquals(10L, settle.winnerPlayerId());
        assertEquals(SettlementReason.DATABASE_RESULT_CONFIRMED, settle.reason());
        assertTrue(settle.confidence() >= 0.85);
    }

    @Test
    void returnsManualReviewWhenContradictionsBlockSettlement() {
        SettlementEvidence evidence = new SettlementEvidence(
                2L,
                new TrackedEventId("tracked-2"),
                identityLock(Instant.parse("2026-04-19T12:00:00Z")),
                List.of(new LiveObservation(
                        SourceId.HR_MKT,
                        Instant.parse("2026-04-19T12:29:00Z"),
                        0.92,
                        MatchPhase.LIVE_LATE,
                        new ScoreState(1, 2, 7, 10, ""),
                        "raw-live",
                        false,
                        "booker-2",
                        "market-2",
                        false,
                        false
                )),
                List.of(),
                List.of(),
                List.of(new OfficialCandidate(
                        SourceId.TTS_POST,
                        201L,
                        Instant.parse("2026-04-19T12:30:00Z"),
                        LocalDate.of(2026, 4, 19),
                        10L,
                        20L,
                        10L,
                        "booker-2",
                        0.95,
                        true,
                        "raw-post"
                )),
                List.of(),
                CoverageState.PARTIAL,
                List.of(),
                0.0,
                0.90,
                Instant.parse("2026-04-19T12:30:30Z")
        );

        Decision decision = engine.decide(evidence, SettlementPolicy.defaults());

        ManualReview manualReview = assertInstanceOf(ManualReview.class, decision);
        assertEquals(SettlementReason.MANUAL_REVIEW_AWAITING, manualReview.reason());
        assertEquals(1, manualReview.contradictions().size());
    }

    @Test
    void holdsOpenWhenOnlySingleUnfinishedLiveSourceExists() {
        SettlementEvidence evidence = new SettlementEvidence(
                3L,
                new TrackedEventId("tracked-3"),
                identityLock(Instant.parse("2026-04-19T12:00:00Z")),
                List.of(new LiveObservation(
                        SourceId.HR_MKT,
                        Instant.parse("2026-04-19T12:10:00Z"),
                        0.91,
                        MatchPhase.LIVE_MID,
                        new ScoreState(1, 1, 7, 6, ""),
                        "raw-live",
                        false,
                        "booker-3",
                        "market-3",
                        true,
                        false
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                CoverageState.PARTIAL,
                List.of(),
                0.0,
                0.91,
                Instant.parse("2026-04-19T12:10:30Z")
        );

        Decision decision = engine.decide(evidence, SettlementPolicy.defaults());

        HoldOpen holdOpen = assertInstanceOf(HoldOpen.class, decision);
        assertEquals(SettlementReason.MANUAL_REVIEW_AWAITING, holdOpen.reason());
    }

    @Test
    void escalatesWhenCoverageIsDarkPastThreshold() {
        SettlementEvidence evidence = new SettlementEvidence(
                4L,
                new TrackedEventId("tracked-4"),
                identityLock(Instant.parse("2026-04-19T12:00:00Z")),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                CoverageState.DARK,
                List.of(),
                0.0,
                0.0,
                Instant.parse("2026-04-19T12:20:00Z")
        );

        Decision decision = engine.decide(evidence, SettlementPolicy.defaults());

        Escalate escalate = assertInstanceOf(Escalate.class, decision);
        assertTrue(escalate.nextSources().contains(SourceId.HR_TGT));
        assertTrue(escalate.nextSources().contains(SourceId.STREAM_CV));
    }

    @Test
    void usesHeuristicAfterOfficialWindowWhenSingleDecisiveScoreRemains() {
        SettlementEvidence evidence = new SettlementEvidence(
                5L,
                new TrackedEventId("tracked-5"),
                identityLock(Instant.parse("2026-04-19T12:00:00Z")),
                List.of(new LiveObservation(
                        SourceId.HR_MKT,
                        Instant.parse("2026-04-19T16:10:00Z"),
                        0.92,
                        MatchPhase.LIVE_LATE,
                        new ScoreState(2, 1, 10, 5, ""),
                        "raw-live",
                        false,
                        "booker-5",
                        "market-5",
                        false,
                        false
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                CoverageState.PARTIAL,
                List.of(),
                0.0,
                0.92,
                Instant.parse("2026-04-19T16:30:00Z")
        );

        Decision decision = engine.decide(evidence, SettlementPolicy.defaults());

        Settle settle = assertInstanceOf(Settle.class, decision);
        assertEquals(SettlementReason.LAST_SCORE_HEURISTIC, settle.reason());
        assertEquals(10L, settle.winnerPlayerId());
    }

    private IdentityLock identityLock(Instant placementTime) {
        return new IdentityLock(
                10L,
                20L,
                placementTime,
                Duration.ofHours(8),
                "booker-1",
                "market-1"
        );
    }
}
