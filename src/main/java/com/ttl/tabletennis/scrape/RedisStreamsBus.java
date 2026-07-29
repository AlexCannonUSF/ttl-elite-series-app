package com.ttl.tabletennis.scrape;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class RedisStreamsBus implements IngestionBus {

    private static final String DEFAULT_STREAM_PREFIX = "ttl";
    /**
     * Default cap for every {@code ttl:*} stream. Sized so a real consumer
     * could realistically replay the last several minutes of events even on
     * the busiest stream ({@code ttl:health} can hit 1 entry/sec). 0 or
     * negative disables the cap entirely (legacy behaviour, not recommended
     * in shadow mode).
     */
    private static final long DEFAULT_MAX_STREAM_LENGTH = 10_000L;
    /**
     * Trim every N publishes instead of every single publish. {@code XTRIM}
     * with {@code MAXLEN ~} is already O(1) amortised, but skipping most
     * publishes shaves a Redis round-trip off the hot path while still
     * keeping the cap close to {@code maxStreamLength} in practice.
     */
    private static final int TRIM_EVERY_N_PUBLISHES = 50;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final IngestMetricsRecorder ingestMetricsRecorder;
    private final String streamPrefix;
    private final boolean recordMetrics;
    private final long maxStreamLength;
    private final java.util.concurrent.atomic.AtomicLong publishCount = new java.util.concurrent.atomic.AtomicLong();

    public RedisStreamsBus(StringRedisTemplate redisTemplate,
                           ObjectMapper objectMapper,
                           IngestMetricsRecorder ingestMetricsRecorder,
                           String streamPrefix) {
        this(redisTemplate, objectMapper, ingestMetricsRecorder, streamPrefix, true, DEFAULT_MAX_STREAM_LENGTH);
    }

    public RedisStreamsBus(StringRedisTemplate redisTemplate,
                           ObjectMapper objectMapper,
                           IngestMetricsRecorder ingestMetricsRecorder,
                           String streamPrefix,
                           long maxStreamLength) {
        this(redisTemplate, objectMapper, ingestMetricsRecorder, streamPrefix, true, maxStreamLength);
    }

    RedisStreamsBus(StringRedisTemplate redisTemplate,
                    ObjectMapper objectMapper,
                    IngestMetricsRecorder ingestMetricsRecorder,
                    String streamPrefix,
                    boolean recordMetrics) {
        this(redisTemplate, objectMapper, ingestMetricsRecorder, streamPrefix, recordMetrics, DEFAULT_MAX_STREAM_LENGTH);
    }

    RedisStreamsBus(StringRedisTemplate redisTemplate,
                    ObjectMapper objectMapper,
                    IngestMetricsRecorder ingestMetricsRecorder,
                    String streamPrefix,
                    boolean recordMetrics,
                    long maxStreamLength) {
        if (redisTemplate == null) {
            throw new IllegalArgumentException("redisTemplate must not be null");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper must not be null");
        }
        if (ingestMetricsRecorder == null) {
            throw new IllegalArgumentException("ingestMetricsRecorder must not be null");
        }
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ingestMetricsRecorder = ingestMetricsRecorder;
        this.streamPrefix = normalizeStreamPrefix(streamPrefix);
        this.recordMetrics = recordMetrics;
        this.maxStreamLength = maxStreamLength;
    }

    @Override
    public void publish(IngestEvent<?> event) {
        if (event == null) {
            return;
        }

        String key = streamKey(event);
        RecordId recordId = redisTemplate.opsForStream().add(key, fieldsFor(event));
        if (recordId == null) {
            throw new IllegalStateException("Redis did not return a stream record id for " + event.topic());
        }
        // Cap stream length so the writer doesn't pile up indefinitely when
        // running in shadow mode (no consumer group has been wired yet). The
        // shadow streams aren't read by anyone — without this cap, ttl:health
        // hits 300k+ entries in a single day and Redis starts pushing back on
        // XADD, which surfaces as "scrape.errors" on the publisher side and
        // a DEGRADED bus status on /api/v3/ops/ingest.
        if (maxStreamLength > 0 && publishCount.incrementAndGet() % TRIM_EVERY_N_PUBLISHES == 0) {
            try {
                redisTemplate.opsForStream().trim(key, maxStreamLength, true);
            } catch (RuntimeException trimEx) {
                // Best-effort: never fail the publish because trim hiccuped.
                // The next publish (within TRIM_EVERY_N_PUBLISHES) will retry.
            }
        }
        if (recordMetrics) {
            ingestMetricsRecorder.recordPublished(event);
        }
    }

    Map<String, String> fieldsFor(IngestEvent<?> event) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("source_id", event.source().id());
        fields.put("topic", event.topic());
        fields.put("observed_at", DateTimeFormatter.ISO_INSTANT.format(event.observedAt()));
        fields.put("confidence", Double.toString(event.confidence()));
        fields.put("correlation_id", event.correlationId());
        fields.put("raw_payload_ref", event.rawPayloadRef());
        fields.put("payload_type", event.payload().getClass().getName());
        fields.put("payload_json", payloadJson(event.payload()));
        return fields;
    }

    String streamKey(IngestEvent<?> event) {
        String topic = event.topic().toLowerCase(Locale.ROOT);
        if (topic.contains("odds")) {
            return streamPrefix + ":odds";
        }
        if (topic.contains("score") || topic.contains("frame") || topic.contains("stale.live")) {
            return streamPrefix + ":scores";
        }
        if (topic.contains("result") || topic.contains("ledger")) {
            return streamPrefix + ":results";
        }
        if (topic.contains("health")) {
            return streamPrefix + ":health";
        }
        if (topic.contains("identity") || topic.contains("ranking")) {
            return streamPrefix + ":identity";
        }
        return streamPrefix + ":" + sanitizeTopic(topic);
    }

    private String payloadJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize ingest payload for Redis Streams", e);
        }
    }

    private static String normalizeStreamPrefix(String streamPrefix) {
        if (streamPrefix == null || streamPrefix.isBlank()) {
            return DEFAULT_STREAM_PREFIX;
        }
        return streamPrefix.trim().toLowerCase(Locale.ROOT);
    }

    private static String sanitizeTopic(String topic) {
        String sanitized = topic == null ? "" : topic
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return sanitized.isBlank() ? "events" : sanitized;
    }
}
