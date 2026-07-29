package com.ttl.tabletennis.settlement;

import com.ttl.tabletennis.scrape.SourceId;
import com.ttl.tabletennis.settlement.observation.LiveObservation;
import com.ttl.tabletennis.settlement.observation.MatchPhase;
import com.ttl.tabletennis.settlement.observation.ScoreState;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultSettlementEngineTests {

    /**
     * Pin the engine's wall-clock to a moment near the fixture timestamps so
     * window-expiry checks (which now compare placement to the LATER of
     * bundleAsOf and the engine's clock) behave as the legacy tests assume.
     * Production uses {@link Clock#systemDefaultZone()}; tests use this
     * fixed clock so we stay deterministic.
     */
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-04-19T12:30:30Z"),
            ZoneOffset.UTC);

    private final DefaultSettlementEngine engine = new DefaultSettlementEngine(
            new AmbiguityScorer(),
            new ContradictionGuard(),
            FIXED_CLOCK
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

    // --- #117 tests: phase-aware void timeout ---

    private static SettlementEvidence evidenceWithPhase(MatchPhase phase,
                                                          Instant placementTime,
                                                          Instant bundleAsOf) {
        return new SettlementEvidence(
                42L,
                new TrackedEventId("tracked-117"),
                new IdentityLock(10L, 20L, placementTime, Duration.ofHours(8),
                        "booker-117", "market-117"),
                List.of(new LiveObservation(
                        SourceId.HR_MKT,
                        bundleAsOf,
                        0.92,
                        phase,
                        new ScoreState(2, 1, 10, 5, ""),
                        "raw-live",
                        false,
                        "booker-117",
                        "market-117",
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
                bundleAsOf
        );
    }

    @Test
    void liveLatePhaseTriggersHeuristicAt90Min_butLiveEarlyDoesNot() {
        // Tight fixed clock so minutesSincePlacement uses bundleAsOf
        // (placement 12:00 → bundleAsOf 13:35 = 95 min elapsed).
        Clock clock = Clock.fixed(Instant.parse("2026-04-19T13:35:00Z"), ZoneOffset.UTC);
        DefaultSettlementEngine engineLocal = new DefaultSettlementEngine(
                new AmbiguityScorer(), new ContradictionGuard(), clock);

        Instant placement = Instant.parse("2026-04-19T12:00:00Z");
        Instant bundleAsOf = Instant.parse("2026-04-19T13:35:00Z");

        // LIVE_LATE at 95 min elapsed → heuristic fires (default LIVE_LATE
        // threshold is 90 — this is the #117 unlock).
        SettlementEvidence late = evidenceWithPhase(MatchPhase.LIVE_LATE, placement, bundleAsOf);
        Decision lateDecision = engineLocal.decide(late, SettlementPolicy.defaults());
        assertInstanceOf(Settle.class, lateDecision,
                "LIVE_LATE phase: 95 min > 90-min threshold → heuristic settle");

        // LIVE_EARLY at the same 95 min → does NOT fire heuristic (LIVE_EARLY
        // threshold is 200), AND official window (180 min) hasn't expired
        // either, so coverage-DARK escalation cannot fire (coverage is PARTIAL
        // here from the live observation). Engine should HoldOpen.
        SettlementEvidence early = evidenceWithPhase(MatchPhase.LIVE_EARLY, placement, bundleAsOf);
        Decision earlyDecision = engineLocal.decide(early, SettlementPolicy.defaults());
        assertInstanceOf(HoldOpen.class, earlyDecision,
                "LIVE_EARLY phase: 95 min < 200-min phase threshold → hold open (no heuristic)");
    }

    @Test
    void liveLatePhaseAt89MinHoldsOpen_thenSettlesAt91Min() {
        // Boundary test: confirm the 90-min LIVE_LATE threshold gates correctly.
        Instant placement = Instant.parse("2026-04-19T12:00:00Z");

        // 89 min — just below the LIVE_LATE threshold.
        Clock clock89 = Clock.fixed(Instant.parse("2026-04-19T13:29:00Z"), ZoneOffset.UTC);
        DefaultSettlementEngine engine89 = new DefaultSettlementEngine(
                new AmbiguityScorer(), new ContradictionGuard(), clock89);
        SettlementEvidence ev89 = evidenceWithPhase(
                MatchPhase.LIVE_LATE, placement, Instant.parse("2026-04-19T13:29:00Z"));
        assertInstanceOf(HoldOpen.class, engine89.decide(ev89, SettlementPolicy.defaults()),
                "LIVE_LATE at 89 min < 90 → hold open");

        // 91 min — just above the threshold.
        Clock clock91 = Clock.fixed(Instant.parse("2026-04-19T13:31:00Z"), ZoneOffset.UTC);
        DefaultSettlementEngine engine91 = new DefaultSettlementEngine(
                new AmbiguityScorer(), new ContradictionGuard(), clock91);
        SettlementEvidence ev91 = evidenceWithPhase(
                MatchPhase.LIVE_LATE, placement, Instant.parse("2026-04-19T13:31:00Z"));
        assertInstanceOf(Settle.class, engine91.decide(ev91, SettlementPolicy.defaults()),
                "LIVE_LATE at 91 min > 90 → heuristic settle");
    }

    @Test
    void heuristicRecordBackCompatConstructorSetsEmptyPhaseMap() {
        SettlementPolicy.Heuristic legacy = new SettlementPolicy.Heuristic(true, 300);
        assertEquals(300, legacy.afterDarkMinutes());
        assertTrue(legacy.phaseAfterDarkMinutes().isEmpty(),
                "back-compat constructor leaves phase map empty");

        // afterDarkMinutesFor(any phase) falls back to legacy threshold
        assertEquals(300, legacy.afterDarkMinutesFor(MatchPhase.LIVE_LATE));
        assertEquals(300, legacy.afterDarkMinutesFor(MatchPhase.PREMATCH));
        assertEquals(300, legacy.afterDarkMinutesFor(null));
    }

    @Test
    void heuristicRecordValidatesNegativePhaseMinutes() {
        java.util.Map<MatchPhase, Integer> bad = new java.util.HashMap<>();
        bad.put(MatchPhase.LIVE_LATE, -1);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new SettlementPolicy.Heuristic(true, 240, bad));
    }
}
