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
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContradictionGuardTests {

    private final ContradictionGuard guard = new ContradictionGuard();

    @Test
    void detectsWinnerDisagreementBetweenTimelineAndOfficialResult() {
        SettlementEvidence evidence = new SettlementEvidence(
                1L,
                new TrackedEventId("tracked-1"),
                identityLock(),
                List.of(new LiveObservation(
                        SourceId.HR_MKT,
                        Instant.parse("2026-04-19T15:10:00Z"),
                        0.92,
                        MatchPhase.LIVE_LATE,
                        new ScoreState(1, 2, 7, 10, "p2"),
                        "raw-live",
                        false,
                        "booker-1",
                        "market-1",
                        false,
                        false
                )),
                List.of(),
                List.of(),
                List.of(new OfficialCandidate(
                        SourceId.TTS_POST,
                        101L,
                        Instant.parse("2026-04-19T15:12:00Z"),
                        LocalDate.of(2026, 4, 19),
                        10L,
                        20L,
                        10L,
                        "booker-1",
                        0.96,
                        true,
                        "raw-official"
                )),
                List.of(),
                CoverageState.PARTIAL,
                List.of(),
                0.0,
                0.0,
                Instant.parse("2026-04-19T15:12:30Z")
        );

        List<Contradiction> contradictions = guard.detect(evidence);

        assertEquals(1, contradictions.size());
        assertEquals(ContradictionKind.WINNER_DISAGREE, contradictions.get(0).kind());
        assertTrue(contradictions.get(0).severity() > ContradictionGuard.DEFAULT_BLOCK_THRESHOLD);
        assertTrue(guard.blocksAutoSettlement(evidence));
    }

    @Test
    void detectsProgressiveScoreRegressionOnSameSource() {
        SettlementEvidence evidence = new SettlementEvidence(
                2L,
                new TrackedEventId("tracked-2"),
                identityLock(),
                List.of(
                        new LiveObservation(
                                SourceId.HR_MKT,
                                Instant.parse("2026-04-19T15:05:00Z"),
                                0.90,
                                MatchPhase.LIVE_MID,
                                new ScoreState(2, 1, 6, 4, "p1"),
                                "raw-1",
                                false,
                                "booker-2",
                                "market-2",
                                true,
                                false
                        ),
                        new LiveObservation(
                                SourceId.HR_MKT,
                                Instant.parse("2026-04-19T15:06:00Z"),
                                0.90,
                                MatchPhase.LIVE_MID,
                                new ScoreState(1, 1, 3, 2, "p2"),
                                "raw-2",
                                false,
                                "booker-2",
                                "market-2",
                                true,
                                false
                        )
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                CoverageState.PARTIAL,
                List.of(),
                0.0,
                0.0,
                Instant.parse("2026-04-19T15:06:30Z")
        );

        List<Contradiction> contradictions = guard.detect(evidence);

        assertEquals(1, contradictions.size());
        assertEquals(ContradictionKind.SCORE_DIVERGENCE, contradictions.get(0).kind());
        assertEquals(0.85, contradictions.get(0).severity(), 0.000001);
    }

    @Test
    void detectsPhaseInversionWhenFinishedConfirmationPrecedesLaterLiveLate() {
        SettlementEvidence evidence = new SettlementEvidence(
                3L,
                new TrackedEventId("tracked-3"),
                identityLock(),
                List.of(new LiveObservation(
                        SourceId.HR_TGT,
                        Instant.parse("2026-04-19T15:20:00Z"),
                        0.89,
                        MatchPhase.LIVE_LATE,
                        new ScoreState(2, 2, 8, 8, "p1"),
                        "raw-live-late",
                        false,
                        "booker-3",
                        "market-3",
                        false,
                        false
                )),
                List.of(),
                List.of(),
                List.of(new OfficialCandidate(
                        SourceId.TTS_PLAYER,
                        301L,
                        Instant.parse("2026-04-19T15:15:00Z"),
                        LocalDate.of(2026, 4, 19),
                        10L,
                        20L,
                        20L,
                        "",
                        0.87,
                        true,
                        "raw-player"
                )),
                List.of(),
                CoverageState.PARTIAL,
                List.of(),
                0.0,
                0.0,
                Instant.parse("2026-04-19T15:20:30Z")
        );

        List<Contradiction> contradictions = guard.detect(evidence);

        assertEquals(1, contradictions.size());
        assertEquals(ContradictionKind.PHASE_MISMATCH, contradictions.get(0).kind());
        assertEquals(0.70, contradictions.get(0).severity(), 0.000001);
    }

    @Test
    void detectsConflictingConfirmationWinners() {
        SettlementEvidence evidence = new SettlementEvidence(
                4L,
                new TrackedEventId("tracked-4"),
                identityLock(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new OfficialCandidate(
                        SourceId.TTS_H2H,
                        401L,
                        Instant.parse("2026-04-19T15:12:00Z"),
                        LocalDate.of(2026, 4, 19),
                        10L,
                        20L,
                        10L,
                        "",
                        0.91,
                        true,
                        "raw-h2h"
                )),
                List.of(new DatabaseCandidate(
                        402L,
                        Instant.parse("2026-04-19T15:13:00Z"),
                        LocalDate.of(2026, 4, 19),
                        10L,
                        20L,
                        20L,
                        "",
                        0.88,
                        true,
                        "raw-db"
                )),
                CoverageState.PARTIAL,
                List.of(),
                0.0,
                0.0,
                Instant.parse("2026-04-19T15:13:30Z")
        );

        List<Contradiction> contradictions = guard.detect(evidence);

        assertEquals(1, contradictions.size());
        assertEquals(ContradictionKind.WINNER_DISAGREE, contradictions.get(0).kind());
        assertEquals(0.95, contradictions.get(0).severity(), 0.000001);
        assertTrue(guard.blocksAutoSettlement(evidence));
    }

    private IdentityLock identityLock() {
        return new IdentityLock(
                10L,
                20L,
                Instant.parse("2026-04-19T14:55:00Z"),
                Duration.ofHours(8),
                "booker-1",
                "market-1"
        );
    }
}
