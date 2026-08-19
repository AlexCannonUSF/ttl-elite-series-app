package com.ttl.tabletennis.scrape;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RawPayloadStoringIngestionBusTests {

    private static final Instant T0 = Instant.parse("2026-05-17T03:04:05Z");

    @Test
    void forwardsEventUnchangedWhenStoreDisabled() {
        RecordingBus inner = new RecordingBus();
        RawPayloadStoringIngestionBus bus = new RawPayloadStoringIngestionBus(
                inner,
                new NoopRawPayloadStore(),
                new ObjectMapper()
        );

        IngestEvent<String> event = event("corr-1", "");
        bus.publish(event);

        assertEquals(1, inner.published.size());
        assertSame(event, inner.published.get(0));
    }

    @Test
    void forwardsEventUnchangedWhenCorrelationIdBlank() {
        RecordingBus inner = new RecordingBus();
        RecordingStore store = new RecordingStore("s3://ttl-raw/HR_MKT/2026-05-17/corr");
        RawPayloadStoringIngestionBus bus = new RawPayloadStoringIngestionBus(inner, store, new ObjectMapper());

        IngestEvent<String> event = event("", "");
        bus.publish(event);

        assertSame(event, inner.published.get(0));
        assertEquals(0, store.calls.size());
    }

    @Test
    void forwardsEventUnchangedWhenRawPayloadRefAlreadyPopulated() {
        RecordingBus inner = new RecordingBus();
        RecordingStore store = new RecordingStore("s3://ttl-raw/HR_MKT/2026-05-17/corr-new");
        RawPayloadStoringIngestionBus bus = new RawPayloadStoringIngestionBus(inner, store, new ObjectMapper());

        IngestEvent<String> event = event("corr-1", "s3://other-bucket/preexisting");
        bus.publish(event);

        assertSame(event, inner.published.get(0));
        assertEquals(0, store.calls.size());
    }

    @Test
    void writesPayloadJsonAndRepublishesWithReturnedRef() {
        RecordingBus inner = new RecordingBus();
        String returnedRef = "s3://ttl-raw/HR_MKT/2026-05-17/corr-1";
        RecordingStore store = new RecordingStore(returnedRef);
        RawPayloadStoringIngestionBus bus = new RawPayloadStoringIngestionBus(inner, store, new ObjectMapper());

        IngestEvent<String> event = event("corr-1", "");
        bus.publish(event);

        assertEquals(1, store.calls.size());
        RecordingStore.Call call = store.calls.get(0);
        assertEquals(SourceId.HR_MKT, call.source());
        assertEquals("corr-1", call.correlationId());
        assertEquals(T0, call.observedAt());
        assertEquals("\"payload\"", new String(call.body()));

        assertEquals(1, inner.published.size());
        IngestEvent<?> forwarded = inner.published.get(0);
        assertEquals(returnedRef, forwarded.rawPayloadRef());
        assertEquals("corr-1", forwarded.correlationId());
        assertEquals(event.topic(), forwarded.topic());
        assertEquals(event.source(), forwarded.source());
        assertEquals(event.observedAt(), forwarded.observedAt());
        assertEquals(event.confidence(), forwarded.confidence());
        assertSame(event.payload(), forwarded.payload());
    }

    @Test
    void forwardsOriginalWhenStorePutThrows() {
        RecordingBus inner = new RecordingBus();
        RawPayloadStore failingStore = new RawPayloadStore() {
            @Override
            public String put(SourceId source, String correlationId, Instant observedAt, byte[] body) {
                throw new RawPayloadStoreException("boom");
            }
        };
        RawPayloadStoringIngestionBus bus = new RawPayloadStoringIngestionBus(inner, failingStore, new ObjectMapper());

        IngestEvent<String> event = event("corr-1", "");
        bus.publish(event);

        assertEquals(1, inner.published.size());
        assertEquals("", inner.published.get(0).rawPayloadRef());
        assertSame(event.payload(), inner.published.get(0).payload());
    }

    @Test
    void forwardsOriginalWhenSerializationFails() throws JsonProcessingException {
        RecordingBus inner = new RecordingBus();
        RecordingStore store = new RecordingStore("s3://ttl-raw/HR_MKT/2026-05-17/corr-x");
        ObjectMapper mapper = new ObjectMapper() {
            @Override
            public byte[] writeValueAsBytes(Object value) throws JsonProcessingException {
                throw new JsonProcessingException("nope") { };
            }
        };
        RawPayloadStoringIngestionBus bus = new RawPayloadStoringIngestionBus(inner, store, mapper);

        IngestEvent<String> event = event("corr-1", "");
        bus.publish(event);

        assertEquals(0, store.calls.size());
        assertSame(event, inner.published.get(0));
    }

    @Test
    void forwardsOriginalWhenStoreReturnsBlankRef() {
        RecordingBus inner = new RecordingBus();
        RecordingStore store = new RecordingStore("");
        RawPayloadStoringIngestionBus bus = new RawPayloadStoringIngestionBus(inner, store, new ObjectMapper());

        IngestEvent<String> event = event("corr-1", "");
        bus.publish(event);

        assertEquals(1, store.calls.size());
        assertSame(event, inner.published.get(0));
    }

    @Test
    void publishNullIsNoop() {
        RecordingBus inner = new RecordingBus();
        RecordingStore store = new RecordingStore("s3://ttl-raw/x/y/z");
        RawPayloadStoringIngestionBus bus = new RawPayloadStoringIngestionBus(inner, store, new ObjectMapper());

        bus.publish(null);

        assertEquals(0, store.calls.size());
        assertEquals(0, inner.published.size());
    }

    @Test
    void constructorRejectsNullDependencies() {
        ObjectMapper mapper = new ObjectMapper();
        RecordingBus bus = new RecordingBus();
        NoopRawPayloadStore store = new NoopRawPayloadStore();

        assertThrows(IllegalArgumentException.class,
                () -> new RawPayloadStoringIngestionBus(null, store, mapper));
        assertThrows(IllegalArgumentException.class,
                () -> new RawPayloadStoringIngestionBus(bus, null, mapper));
        assertThrows(IllegalArgumentException.class,
                () -> new RawPayloadStoringIngestionBus(bus, store, null));
    }

    private static IngestEvent<String> event(String correlationId, String rawPayloadRef) {
        return new IngestEvent<>(
                SourceId.HR_MKT,
                "odds.updated",
                T0,
                0.91,
                correlationId,
                rawPayloadRef,
                "payload"
        );
    }

    private static final class RecordingBus implements IngestionBus {
        final List<IngestEvent<?>> published = new ArrayList<>();

        @Override
        public void publish(IngestEvent<?> event) {
            published.add(event);
        }
    }

    private static final class RecordingStore implements RawPayloadStore {
        record Call(SourceId source, String correlationId, Instant observedAt, byte[] body) { }

        final List<Call> calls = new ArrayList<>();
        private final String returnedRef;

        RecordingStore(String returnedRef) {
            this.returnedRef = returnedRef;
        }

        @Override
        public String put(SourceId source, String correlationId, Instant observedAt, byte[] uncompressedBody) {
            calls.add(new Call(source, correlationId, observedAt, uncompressedBody));
            return returnedRef;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    }
}
