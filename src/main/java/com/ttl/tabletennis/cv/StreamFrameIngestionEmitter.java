package com.ttl.tabletennis.cv;

import com.ttl.tabletennis.scrape.IngestEvent;
import com.ttl.tabletennis.scrape.IngestionBus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

@Component
public class StreamFrameIngestionEmitter {

    private final IngestionBus ingestionBus;
    private final StreamFrameEventFactory eventFactory;

    public StreamFrameIngestionEmitter(IngestionBus ingestionBus,
                                       StreamFrameEventFactory eventFactory) {
        if (ingestionBus == null) {
            throw new IllegalArgumentException("ingestionBus must not be null");
        }
        if (eventFactory == null) {
            throw new IllegalArgumentException("eventFactory must not be null");
        }
        this.ingestionBus = ingestionBus;
        this.eventFactory = eventFactory;
    }

    public Optional<IngestEvent<StreamFrameObservationPayload>> emit(String correlationId,
                                                                    Instant observedAt,
                                                                    StreamFrameObservationPayload payload) {
        if (payload == null) {
            return Optional.empty();
        }
        IngestEvent<StreamFrameObservationPayload> event = eventFactory.event(correlationId, observedAt, payload);
        ingestionBus.publish(event);
        return Optional.of(event);
    }

    public Optional<IngestEvent<StreamFrameObservationPayload>> emit(String correlationId,
                                                                    Instant observedAt,
                                                                    Optional<StreamFrameObservationPayload> payload) {
        if (payload == null || payload.isEmpty()) {
            return Optional.empty();
        }
        return emit(correlationId, observedAt, payload.get());
    }
}
