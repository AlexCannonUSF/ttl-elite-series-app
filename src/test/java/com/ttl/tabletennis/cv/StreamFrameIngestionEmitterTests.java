package com.ttl.tabletennis.cv;

import com.ttl.tabletennis.scrape.IngestEvent;
import com.ttl.tabletennis.scrape.IngestionBus;
import com.ttl.tabletennis.scrape.SourceId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamFrameIngestionEmitterTests {

    @Test
    void emitPublishesEventThroughBus() {
        RecordingBus bus = new RecordingBus();
        StreamFrameIngestionEmitter emitter = new StreamFrameIngestionEmitter(bus, new StreamFrameEventFactory());

        StreamFrameObservationPayload payload = new StreamFrameObservationPayload(
                1, 0, 4, 2,
                ServerSide.UNKNOWN.name(),
                "RALLY",
                0.93,
                "tpl-1",
                "paddle",
                "match-1:42"
        );

        Optional<IngestEvent<StreamFrameObservationPayload>> result = emitter.emit(
                "corr-stream-1",
                Instant.parse("2026-04-19T12:00:02Z"),
                payload
        );

        assertTrue(result.isPresent());
        assertEquals(1, bus.published.size());
        IngestEvent<?> event = bus.published.get(0);
        assertEquals(SourceId.STREAM_CV, event.source());
        assertEquals(StreamFrameEventFactory.TOPIC, event.topic());
        assertEquals("corr-stream-1", event.correlationId());
        assertEquals(Instant.parse("2026-04-19T12:00:02Z"), event.observedAt());
        assertSame(payload, event.payload());
    }

    @Test
    void emitFallsBackToFrameIdWhenCorrelationIdMissing() {
        RecordingBus bus = new RecordingBus();
        StreamFrameIngestionEmitter emitter = new StreamFrameIngestionEmitter(bus, new StreamFrameEventFactory());

        StreamFrameObservationPayload payload = new StreamFrameObservationPayload(
                0, 0, 11, 9,
                ServerSide.UNKNOWN.name(),
                "DEUCE",
                0.91,
                "tpl-2",
                "paddle",
                "match-2:7"
        );

        emitter.emit("", Instant.parse("2026-04-19T12:00:05Z"), payload);

        assertEquals("match-2:7", bus.published.get(0).correlationId());
    }

    @Test
    void emitWithNullPayloadIsNoop() {
        RecordingBus bus = new RecordingBus();
        StreamFrameIngestionEmitter emitter = new StreamFrameIngestionEmitter(bus, new StreamFrameEventFactory());

        Optional<IngestEvent<StreamFrameObservationPayload>> result = emitter.emit(
                "corr-empty",
                Instant.parse("2026-04-19T12:00:05Z"),
                (StreamFrameObservationPayload) null
        );

        assertTrue(result.isEmpty());
        assertTrue(bus.published.isEmpty());
    }

    @Test
    void emitWithEmptyOptionalIsNoop() {
        RecordingBus bus = new RecordingBus();
        StreamFrameIngestionEmitter emitter = new StreamFrameIngestionEmitter(bus, new StreamFrameEventFactory());

        Optional<IngestEvent<StreamFrameObservationPayload>> result = emitter.emit(
                "corr-empty",
                Instant.parse("2026-04-19T12:00:05Z"),
                Optional.empty()
        );

        assertTrue(result.isEmpty());
        assertTrue(bus.published.isEmpty());
    }

    @Test
    void emitWithOptionalDelegatesToPayloadOverload() {
        RecordingBus bus = new RecordingBus();
        StreamFrameIngestionEmitter emitter = new StreamFrameIngestionEmitter(bus, new StreamFrameEventFactory());

        StreamFrameObservationPayload payload = new StreamFrameObservationPayload(
                2, 1, 11, 7,
                ServerSide.UNKNOWN.name(),
                "GAME_BALL",
                0.97,
                "tpl-3",
                "paddle",
                "match-3:21"
        );

        emitter.emit("corr-stream-3", Instant.parse("2026-04-19T12:00:08Z"), Optional.of(payload));

        assertEquals(1, bus.published.size());
        assertSame(payload, bus.published.get(0).payload());
    }

    @Test
    void constructorRejectsNullDependencies() {
        assertThrows(IllegalArgumentException.class,
                () -> new StreamFrameIngestionEmitter(null, new StreamFrameEventFactory()));
        assertThrows(IllegalArgumentException.class,
                () -> new StreamFrameIngestionEmitter(new RecordingBus(), null));
    }

    @Test
    void emitDoesNotPublishWhenFactoryReceivesNullPayloadStillProtectsBus() {
        RecordingBus bus = new RecordingBus();
        StreamFrameIngestionEmitter emitter = new StreamFrameIngestionEmitter(bus, new StreamFrameEventFactory());

        emitter.emit("c", Instant.parse("2026-04-19T12:00:09Z"), (StreamFrameObservationPayload) null);

        assertFalse(bus.published.stream().anyMatch(e -> e == null));
    }

    private static final class RecordingBus implements IngestionBus {

        final List<IngestEvent<?>> published = new ArrayList<>();

        @Override
        public void publish(IngestEvent<?> event) {
            published.add(event);
        }
    }
}
