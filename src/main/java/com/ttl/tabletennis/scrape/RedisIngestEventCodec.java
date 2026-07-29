package com.ttl.tabletennis.scrape;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ttl.tabletennis.cv.StreamFrameObservationPayload;
import com.ttl.tabletennis.model.MatchOdds;
import com.ttl.tabletennis.settlement.recovery.StaleLiveRecoveryService;

import java.time.Instant;
import java.util.Map;

/**
 * Reconstructs the typed {@link IngestEvent} envelope written by
 * {@link RedisStreamsBus}. Payload types are explicitly allow-listed so a
 * value injected into Redis cannot ask Jackson to instantiate an arbitrary
 * application class.
 */
public class RedisIngestEventCodec {

    private static final Map<String, Class<?>> PAYLOAD_TYPES = Map.of(
            MatchOdds.class.getName(), MatchOdds.class,
            MirrorObservationPayload.class.getName(), MirrorObservationPayload.class,
            ItftWttHistoricalPayload.class.getName(), ItftWttHistoricalPayload.class,
            TtSeriesScraper.OfficialLedgerMatch.class.getName(), TtSeriesScraper.OfficialLedgerMatch.class,
            FeedHealth.class.getName(), FeedHealth.class,
            StreamFrameObservationPayload.class.getName(), StreamFrameObservationPayload.class,
            StaleLiveRecoveryService.StaleLiveDetectedPayload.class.getName(),
            StaleLiveRecoveryService.StaleLiveDetectedPayload.class
    );

    private final ObjectMapper objectMapper;

    public RedisIngestEventCodec(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper must not be null");
        }
        this.objectMapper = objectMapper;
    }

    public IngestEvent<?> decode(Map<?, ?> rawFields) {
        if (rawFields == null || rawFields.isEmpty()) {
            throw new IllegalArgumentException("Redis stream record has no fields");
        }

        String sourceValue = required(rawFields, "source_id");
        SourceId source = SourceId.fromValue(sourceValue)
                .orElseThrow(() -> new IllegalArgumentException("Unknown source_id: " + sourceValue));
        String topic = required(rawFields, "topic");
        Instant observedAt = parseInstant(required(rawFields, "observed_at"));
        double confidence = parseConfidence(required(rawFields, "confidence"));
        String payloadType = required(rawFields, "payload_type");
        Class<?> payloadClass = PAYLOAD_TYPES.get(payloadType);
        if (payloadClass == null) {
            throw new IllegalArgumentException("Unsupported payload_type: " + payloadType);
        }

        Object payload;
        try {
            payload = objectMapper.readValue(required(rawFields, "payload_json"), payloadClass);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid payload_json for " + payloadType, ex);
        }

        return new IngestEvent<>(
                source,
                topic,
                observedAt,
                confidence,
                optional(rawFields, "correlation_id"),
                optional(rawFields, "raw_payload_ref"),
                payload
        );
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid observed_at: " + value, ex);
        }
    }

    private static double parseConfidence(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid confidence: " + value, ex);
        }
    }

    private static String required(Map<?, ?> fields, String key) {
        String value = optional(fields, key);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Redis stream record is missing " + key);
        }
        return value;
    }

    private static String optional(Map<?, ?> fields, String key) {
        Object value = fields.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
