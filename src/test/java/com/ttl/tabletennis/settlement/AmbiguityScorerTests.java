package com.ttl.tabletennis.settlement;

import com.ttl.tabletennis.scrape.SourceId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmbiguityScorerTests {

    private final AmbiguityScorer scorer = new AmbiguityScorer();

    @Test
    void exactBookerMatchReducesAmbiguityBelowAutoBlockThreshold() {
        SettlementEvidence evidence = new SettlementEvidence(
                1L,
                new TrackedEventId("tracked-1"),
                identityLock(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new OfficialCandidate(
                        SourceId.TTS_POST,
                        101L,
                        Instant.parse("2026-04-19T16:00:00Z"),
                        LocalDate.of(2026, 4, 19),
                        10L,
                        20L,
                        10L,
                        "booker-1",
                        0.9,
                        true,
                        "raw-post"
                )),
                List.of(),
                CoverageState.PARTIAL,
                List.of(),
                0.0,
                0.0,
                Instant.parse("2026-04-19T16:01:00Z")
        );

        AmbiguityAssessment assessment = scorer.assess(evidence);

        assertEquals(1, assessment.matchingCandidateCount());
        assertEquals(1, assessment.exactBookerMatchCount());
        assertEquals(AmbiguityBand.UNAMBIGUOUS, assessment.band());
        assertTrue(assessment.score() < 0.3);
    }

    @Test
    void multipleSamePairCandidatesEscalateToManualReviewBand() {
        SettlementEvidence evidence = new SettlementEvidence(
                2L,
                new TrackedEventId("tracked-2"),
                identityLock(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        candidate(201L, LocalDate.of(2026, 4, 19), ""),
                        candidate(202L, LocalDate.of(2026, 4, 19), ""),
                        candidate(203L, LocalDate.of(2026, 4, 19), "")
                ),
                List.of(),
                CoverageState.PARTIAL,
                List.of(),
                0.0,
                0.0,
                Instant.parse("2026-04-19T16:01:00Z")
        );

        AmbiguityAssessment assessment = scorer.assess(evidence);

        assertEquals(3, assessment.matchingCandidateCount());
        assertEquals(0, assessment.exactBookerMatchCount());
        assertEquals(AmbiguityBand.MANUAL_REVIEW, assessment.band());
        assertEquals(1.0, assessment.score(), 0.000001);
    }

    @Test
    void mismatchedPairOrDateIsIgnored() {
        SettlementEvidence evidence = new SettlementEvidence(
                3L,
                new TrackedEventId("tracked-3"),
                identityLock(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        candidate(301L, LocalDate.of(2026, 4, 20), ""),
                        new OfficialCandidate(
                                SourceId.TTS_PLAYER,
                                302L,
                                Instant.parse("2026-04-19T16:00:00Z"),
                                LocalDate.of(2026, 4, 19),
                                10L,
                                99L,
                                10L,
                                "",
                                0.8,
                                true,
                                "raw-player"
                        )
                ),
                List.of(new DatabaseCandidate(
                        401L,
                        Instant.parse("2026-04-19T16:05:00Z"),
                        LocalDate.of(2026, 4, 19),
                        20L,
                        10L,
                        20L,
                        "booker-1",
                        0.85,
                        true,
                        "raw-db"
                )),
                CoverageState.PARTIAL,
                List.of(),
                0.0,
                0.0,
                Instant.parse("2026-04-19T16:06:00Z")
        );

        AmbiguityAssessment assessment = scorer.assess(evidence);

        assertEquals(1, assessment.matchingCandidateCount());
        assertEquals(1, assessment.exactBookerMatchCount());
        assertEquals(AmbiguityBand.UNAMBIGUOUS, assessment.band());
    }

    @Test
    void bandThresholdsMatchSpec() {
        assertEquals(AmbiguityBand.UNAMBIGUOUS, scorer.bandFor(0.29));
        assertEquals(AmbiguityBand.REQUIRES_STRONG_EVIDENCE, scorer.bandFor(0.30));
        assertEquals(AmbiguityBand.REQUIRES_STRONG_EVIDENCE, scorer.bandFor(0.69));
        assertEquals(AmbiguityBand.MANUAL_REVIEW, scorer.bandFor(0.70));
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

    private OfficialCandidate candidate(long matchId, LocalDate matchDate, String bookerEventId) {
        return new OfficialCandidate(
                SourceId.TTS_H2H,
                matchId,
                Instant.parse("2026-04-19T16:00:00Z"),
                matchDate,
                10L,
                20L,
                10L,
                bookerEventId,
                0.85,
                true,
                "raw-h2h-" + matchId
        );
    }
}
