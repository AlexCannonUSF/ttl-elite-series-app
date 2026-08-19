package com.ttl.tabletennis.scrape;

import java.time.Instant;

public record IngestEvent<T>(SourceId source,
                             String topic,
                             Instant observedAt,
                             double confidence,
                             String correlationId,
                             String rawPayloadRef,
                             T payload) {

    public IngestEvent {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        topic = requireText(topic, "topic");
        observedAt = observedAt == null ? Instant.now() : observedAt;
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0.0 and 1.0");
        }
        correlationId = correlationId == null ? "" : correlationId.trim();
        rawPayloadRef = rawPayloadRef == null ? "" : rawPayloadRef.trim();
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
