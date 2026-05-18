package com.ttl.tabletennis.scrape;

import com.ttl.tabletennis.domain.MirrorObservation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MirrorObservationFactoryTests {

    private final MirrorObservationFactory factory = new MirrorObservationFactory();

    @Test
    void fromPayloadMapsMirrorEventIntoPersistableObservation() {
        MirrorObservationPayload payload = new MirrorObservationPayload(
                "tracked-123",
                "sofa-456",
                "Adam Staniczek",
                "Dariusz Maszczynski",
                "TT Cup",
                "LIVE_LATE",
                2,
                2,
                9,
                7,
                "P1",
                false,
                "{\"event\":true}"
        );

        IngestEvent<MirrorObservationPayload> event = new IngestEvent<>(
                SourceId.SOFASCORE,
                "score.observed",
                Instant.parse("2026-04-19T18:45:00Z"),
                0.78,
                "corr-mirror",
                "raw://mirror/1",
                payload
        );

        MirrorObservation observation = factory.fromPayload(event);

        assertEquals("tracked-123", observation.getTrackedEventId());
        assertEquals(SourceId.SOFASCORE, observation.getSourceId());
        assertEquals(LocalDateTime.ofInstant(event.observedAt(), ZoneOffset.UTC), observation.getObservedAt());
        assertEquals("LIVE_LATE", observation.getPhase());
        assertEquals(2, observation.getGamesP1());
        assertEquals(7, observation.getPointsP2());
        assertEquals("P1", observation.getServer());
        assertEquals(Boolean.FALSE, observation.getCompletionSignal());
        assertEquals(0.78, observation.getConfidence(), 1.0e-9);
        assertEquals("corr-mirror", observation.getCorrelationId());
        assertEquals("{\"event\":true}", observation.getPayloadJson());
        assertEquals("raw://mirror/1", observation.getRawPayloadRef());
        assertTrue(observation.getId() == null);
    }
}
