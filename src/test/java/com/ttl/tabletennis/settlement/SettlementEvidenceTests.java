package com.ttl.tabletennis.settlement;

import com.ttl.tabletennis.scrape.SourceId;
import com.ttl.tabletennis.settlement.observation.LiveObservation;
import com.ttl.tabletennis.settlement.observation.MatchPhase;
import com.ttl.tabletennis.settlement.observation.MirrorObservation;
import com.ttl.tabletennis.settlement.observation.Observation;
import com.ttl.tabletennis.settlement.observation.OfficialObservation;
import com.ttl.tabletennis.settlement.observation.DatabaseObservation;
import com.ttl.tabletennis.settlement.observation.ScoreState;
import com.ttl.tabletennis.settlement.observation.StreamObservation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementEvidenceTests {

    @Test
    void settlementEvidenceSortsAndDefensivelyCopiesObservations() {
        LiveObservation lateLive = new LiveObservation(
                SourceId.HR_MKT,
                Instant.parse("2026-04-19T15:05:00Z"),
                0.91,
                MatchPhase.LIVE_LATE,
                new ScoreState(2, 1, 8, 6, "p1"),
                "raw-live-2",
                false,
                "booker-1",
                "market-1",
                true,
                false
        );
        LiveObservation earlyLive = new LiveObservation(
                SourceId.HR_TGT,
                Instant.parse("2026-04-19T15:01:00Z"),
                0.95,
                MatchPhase.LIVE_EARLY,
                new ScoreState(0, 0, 3, 1, "p2"),
                "raw-live-1",
                false,
                "booker-1",
                "market-1",
                false,
                false
        );
        MirrorObservation mirrorObservation = new MirrorObservation(
                SourceId.SOFASCORE,
                Instant.parse("2026-04-19T15:03:00Z"),
                0.82,
                MatchPhase.LIVE_MID,
                new ScoreState(1, 1, 5, 4, "p2"),
                "raw-mirror",
                false,
                "mirror-77"
        );
        StreamObservation streamObservation = new StreamObservation(
                SourceId.STREAM_CV,
                Instant.parse("2026-04-19T15:04:00Z"),
                0.88,
                MatchPhase.LIVE_MID,
                new ScoreState(1, 1, 7, 5, "p1"),
                "raw-stream",
                false,
                "route-1",
                "wstt.generic.v1",
                3
        );

        List<LiveObservation> liveObservations = new ArrayList<>(List.of(lateLive, earlyLive));
        SettlementEvidence evidence = new SettlementEvidence(
                99L,
                new TrackedEventId(" tracked-99 "),
                new IdentityLock(
                        10L,
                        20L,
                        Instant.parse("2026-04-19T14:55:00Z"),
                        Duration.ofMinutes(90),
                        " booker-1 ",
                        " market-1 "
                ),
                liveObservations,
                List.of(mirrorObservation),
                List.of(streamObservation),
                List.of(new OfficialCandidate(
                        SourceId.TTS_POST,
                        555L,
                        Instant.parse("2026-04-19T16:00:00Z"),
                        LocalDate.of(2026, 4, 19),
                        10L,
                        20L,
                        10L,
                        "booker-1",
                        0.93,
                        true,
                        "raw-official"
                )),
                List.of(new DatabaseCandidate(
                        777L,
                        Instant.parse("2026-04-19T16:01:00Z"),
                        LocalDate.of(2026, 4, 19),
                        10L,
                        20L,
                        10L,
                        "booker-1",
                        0.89,
                        true,
                        "raw-db"
                )),
                CoverageState.PARTIAL,
                List.of(),
                0.22,
                0.86,
                Instant.parse("2026-04-19T16:02:00Z")
        );

        liveObservations.clear();

        assertEquals("tracked-99", evidence.trackedEventId().value());
        assertEquals("booker-1", evidence.identityLock().bookerEventId());
        assertEquals("market-1", evidence.identityLock().bookerMarketId());
        assertEquals(2, evidence.liveObservations().size());
        assertEquals(Instant.parse("2026-04-19T15:01:00Z"), evidence.liveObservations().get(0).observedAt());
        assertTrue(evidence.hasAnyObservation());
        assertFalse(evidence.hasContradictions());

        List<Observation> allObservations = evidence.allObservations();
        assertEquals(6, allObservations.size());
        assertInstanceOf(LiveObservation.class, allObservations.get(0));
        assertInstanceOf(MirrorObservation.class, allObservations.get(1));
        assertInstanceOf(StreamObservation.class, allObservations.get(2));
        assertInstanceOf(OfficialObservation.class, allObservations.get(4));
        assertInstanceOf(DatabaseObservation.class, allObservations.get(5));
        assertEquals(6, evidence.distinctSources().size());
        assertTrue(evidence.distinctSources().contains(SourceId.INTERNAL_DB));
        assertTrue(evidence.distinctSources().contains(SourceId.TTS_POST));
    }

    @Test
    void liveObservationRejectsNonSportsbookSource() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> new LiveObservation(
                SourceId.SOFASCORE,
                Instant.parse("2026-04-19T15:05:00Z"),
                0.5,
                MatchPhase.UNKNOWN,
                ScoreState.unknown(),
                "",
                false,
                "booker-1",
                "market-1",
                false,
                false
        ));

        assertTrue(ex.getMessage().contains("requires source tier T1_SPORTSBOOK"));
    }

    @Test
    void scoreStateRejectsNegativeNumbers() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new ScoreState(-1, 0, null, null, "p1"));

        assertTrue(ex.getMessage().contains("gamesP1"));
    }

    @Test
    void settlementEvidenceRejectsOutOfRangeConfidenceAndAmbiguity() {
        IllegalArgumentException ambiguityEx = assertThrows(IllegalArgumentException.class, () -> new SettlementEvidence(
                1L,
                new TrackedEventId("tracked-1"),
                identityLock(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                CoverageState.FULL,
                List.of(),
                1.1,
                0.5,
                Instant.parse("2026-04-19T16:00:00Z")
        ));
        IllegalArgumentException confidenceEx = assertThrows(IllegalArgumentException.class, () -> new SettlementEvidence(
                1L,
                new TrackedEventId("tracked-1"),
                identityLock(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                CoverageState.FULL,
                List.of(),
                0.5,
                1.1,
                Instant.parse("2026-04-19T16:00:00Z")
        ));

        assertTrue(ambiguityEx.getMessage().contains("ambiguityScore"));
        assertTrue(confidenceEx.getMessage().contains("confidence"));
    }

    @Test
    void officialCandidateRejectsInternalDatabaseSource() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> new OfficialCandidate(
                SourceId.INTERNAL_DB,
                100L,
                Instant.parse("2026-04-19T16:00:00Z"),
                LocalDate.of(2026, 4, 19),
                10L,
                20L,
                10L,
                "booker-1",
                0.9,
                true,
                "raw"
        ));

        assertTrue(ex.getMessage().contains("non-database T4 confirmation sources"));
    }

    private IdentityLock identityLock() {
        return new IdentityLock(
                10L,
                20L,
                Instant.parse("2026-04-19T14:55:00Z"),
                Duration.ofMinutes(90),
                "booker-1",
                "market-1"
        );
    }
}
