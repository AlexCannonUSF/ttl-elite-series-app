package com.ttl.tabletennis.scrape;

import com.ttl.tabletennis.config.FeatureFlagCatalog;
import com.ttl.tabletennis.domain.IngestDlqEntry;
import com.ttl.tabletennis.repository.IngestDlqRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Consumer-group bridge between Redis Streams and the existing typed Spring
 * event listeners.
 *
 * <p>In {@code shadow} mode records are decoded and acknowledged without
 * dispatch, proving that the serialized contract is replayable while the
 * in-process bus remains authoritative. In {@code on} mode the same consumer
 * publishes the reconstructed event to Spring before acknowledging it. A
 * decode or dispatch failure is persisted to {@code ingest_dlq}; a record is
 * acknowledged only after either successful processing or a successful DLQ
 * write.</p>
 */
@Component
public class RedisStreamsConsumer {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamsConsumer.class);
    private static final List<String> STREAM_FAMILIES = List.of(
            "odds", "scores", "results", "health", "identity"
    );

    private final FeatureFlagCatalog featureFlagCatalog;
    private final Optional<StringRedisTemplate> redisTemplate;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final IngestDlqRepository ingestDlqRepository;
    private final RedisIngestEventCodec codec;
    private final String streamPrefix;
    private final String groupName;
    private final String consumerName;
    private final long batchSize;
    private final AtomicBoolean groupsReady = new AtomicBoolean(false);
    private final Counter validatedCounter;
    private final Counter decodedCounter;
    private final Counter dispatchedCounter;
    private final Counter acknowledgedCounter;
    private final Counter rejectedCounter;
    private final Counter dlqCounter;
    private final Counter pollFailureCounter;
    private final AtomicLong heartbeatEpochMs = new AtomicLong();
    private final AtomicLong lastProcessedEpochMs = new AtomicLong();
    private final AtomicLong latestEventAgeMs = new AtomicLong();

    public RedisStreamsConsumer(
            FeatureFlagCatalog featureFlagCatalog,
            Optional<StringRedisTemplate> redisTemplate,
            ApplicationEventPublisher applicationEventPublisher,
            IngestDlqRepository ingestDlqRepository,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${ttl.ingestion.redis.streamPrefix:ttl}") String streamPrefix,
            @Value("${ttl.ingestion.redis.consumer.group:ttl-app}") String groupName,
            @Value("${ttl.ingestion.redis.consumer.name:ttl-app-1}") String consumerName,
            @Value("${ttl.ingestion.redis.consumer.batchSize:100}") long batchSize) {
        this.featureFlagCatalog = featureFlagCatalog;
        this.redisTemplate = redisTemplate == null ? Optional.empty() : redisTemplate;
        this.applicationEventPublisher = applicationEventPublisher;
        this.ingestDlqRepository = ingestDlqRepository;
        this.codec = new RedisIngestEventCodec(objectMapper);
        this.streamPrefix = normalize(streamPrefix, "ttl");
        this.groupName = normalize(groupName, "ttl-app");
        this.consumerName = normalize(consumerName, "ttl-app-1");
        this.batchSize = Math.max(1L, batchSize);
        this.validatedCounter = counter(meterRegistry, "validated");
        this.decodedCounter = counter(meterRegistry, "decoded");
        this.dispatchedCounter = counter(meterRegistry, "dispatched");
        this.acknowledgedCounter = counter(meterRegistry, "acknowledged");
        this.rejectedCounter = counter(meterRegistry, "rejected");
        this.dlqCounter = counter(meterRegistry, "dlq");
        this.pollFailureCounter = counter(meterRegistry, "poll_failure");
        meterRegistry.gauge("ttl.ingest.redis.consumer.heartbeat.epoch_ms", heartbeatEpochMs);
        meterRegistry.gauge("ttl.ingest.redis.consumer.last_processed.epoch_ms", lastProcessedEpochMs);
        meterRegistry.gauge("ttl.ingest.redis.consumer.latest_event.age_ms", latestEventAgeMs);
    }

    @Scheduled(
            fixedDelayString = "${ttl.ingestion.redis.consumer.pollDelayMs:250}",
            scheduler = "ttlRedisConsumerScheduler"
    )
    public void poll() {
        try {
            pollOnce();
        } catch (RuntimeException ex) {
            groupsReady.set(false);
            pollFailureCounter.increment();
            log.warn("[ingestion-consumer] poll failed: {}", ex.getMessage());
        }
    }

    void pollOnce() {
        String mode = featureFlagCatalog.stateOf(FeatureFlagCatalog.REDIS_STREAMS_FLAG);
        if (!"shadow".equals(mode) && !"on".equals(mode)) {
            return;
        }
        if (redisTemplate.isEmpty()) {
            return;
        }

        ensureGroups();
        consume(mode, ReadOffset.from("0"));
        consume(mode, ReadOffset.lastConsumed());
        heartbeatEpochMs.set(System.currentTimeMillis());
    }

    private void consume(String mode, ReadOffset readOffset) {
        StringRedisTemplate template = redisTemplate.orElseThrow();
        StreamOperations<String, Object, Object> streamOps = template.opsForStream();
        @SuppressWarnings("unchecked")
        StreamOffset<String>[] offsets = STREAM_FAMILIES.stream()
                .map(family -> StreamOffset.create(streamKey(family), readOffset))
                .toArray(StreamOffset[]::new);

        List<MapRecord<String, Object, Object>> records = streamOps.read(
                Consumer.from(groupName, consumerName),
                StreamReadOptions.empty().count(batchSize),
                offsets
        );
        if (records == null || records.isEmpty()) {
            return;
        }
        for (MapRecord<String, Object, Object> record : records) {
            handleRecord(record, mode);
        }
    }

    void handleRecord(MapRecord<String, Object, Object> record, String mode) {
        IngestEvent<?> event;
        try {
            event = codec.decode(record.getValue());
            decodedCounter.increment();
            validatedCounter.increment();
            if ("on".equals(mode)) {
                applicationEventPublisher.publishEvent(event);
                dispatchedCounter.increment();
            }
        } catch (RuntimeException ex) {
            rejectedCounter.increment();
            if (writeDlq(record, ex)) {
                dlqCounter.increment();
                acknowledge(record);
            }
            return;
        }
        // ACK failures are transport failures, not malformed business events;
        // leave the record pending so the next poll can retry it.
        acknowledge(record);
        Instant now = Instant.now();
        lastProcessedEpochMs.set(now.toEpochMilli());
        latestEventAgeMs.set(Math.max(0L, Duration.between(event.observedAt(), now).toMillis()));
    }

    private void ensureGroups() {
        if (groupsReady.get()) {
            return;
        }
        StringRedisTemplate template = redisTemplate.orElseThrow();
        for (String family : STREAM_FAMILIES) {
            String key = streamKey(family);
            try {
                template.execute((RedisCallback<String>) connection ->
                        connection.streamCommands().xGroupCreate(
                                template.getStringSerializer().serialize(key),
                                groupName,
                                ReadOffset.latest(),
                                true
                        )
                );
            } catch (DataAccessException ex) {
                if (!isBusyGroup(ex)) {
                    throw ex;
                }
            }
        }
        groupsReady.set(true);
        log.info("[ingestion-consumer] consumer groups ready; group={} consumer={} streams={}",
                groupName, consumerName, STREAM_FAMILIES.size());
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        Long acknowledged = redisTemplate.orElseThrow().opsForStream().acknowledge(
                record.getStream(),
                groupName,
                record.getId()
        );
        if (acknowledged == null || acknowledged <= 0L) {
            throw new IllegalStateException(
                    "Redis did not acknowledge stream record " + record.getStream() + "/" + record.getId());
        }
        acknowledgedCounter.increment();
    }

    private boolean writeDlq(MapRecord<String, Object, Object> record, RuntimeException failure) {
        try {
            IngestDlqEntry entry = new IngestDlqEntry();
            entry.setTopic(truncate(field(record, "topic", "redis.decode"), 64));
            entry.setSourceId(SourceId.fromValue(field(record, "source_id", ""))
                    .orElse(SourceId.INTERNAL_DB));
            entry.setCorrelationId(truncate(field(record, "correlation_id", ""), 64));
            entry.setPayloadJson(field(record, "payload_json", "{}"));
            entry.setFailureCount(1);
            entry.setLastError(failure.getClass().getSimpleName() + ": " + safeMessage(failure));
            entry.setArrivedAt(LocalDateTime.now(ZoneOffset.UTC));
            ingestDlqRepository.save(entry);
            log.warn("[ingestion-consumer] moved record to DLQ stream={} id={} error={}",
                    record.getStream(), record.getId(), safeMessage(failure));
            return true;
        } catch (RuntimeException dlqFailure) {
            log.error("[ingestion-consumer] could not persist DLQ record stream={} id={}: {}",
                    record.getStream(), record.getId(), safeMessage(dlqFailure));
            return false;
        }
    }

    private String streamKey(String family) {
        return streamPrefix + ":" + family;
    }

    private static Counter counter(MeterRegistry meterRegistry, String outcome) {
        return Counter.builder("ttl.ingest.redis.consumer.records")
                .description("Redis ingestion consumer records by outcome")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    private static String field(MapRecord<String, Object, Object> record,
                                String key,
                                String fallback) {
        Object value = record.getValue().get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean isBusyGroup(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toUpperCase(Locale.ROOT).contains("BUSYGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        return message == null || message.isBlank() ? "unknown failure" : message;
    }
}
