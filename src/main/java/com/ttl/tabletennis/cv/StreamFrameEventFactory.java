package com.ttl.tabletennis.cv;

import com.ttl.tabletennis.scrape.IngestEvent;
import com.ttl.tabletennis.scrape.SourceId;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class StreamFrameEventFactory {

    public static final String TOPIC = "stream.frame";

    public IngestEvent<StreamFrameObservationPayload> event(String correlationId,
                                                            Instant observedAt,
                                                            StreamFrameObservationPayload payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        return new IngestEvent<>(
                SourceId.STREAM_CV,
                TOPIC,
                observedAt == null ? Instant.now() : observedAt,
                payload.confidence(),
                correlationId == null || correlationId.isBlank() ? payload.frameId() : correlationId.trim(),
                "",
                payload
        );
    }
}
