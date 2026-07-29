package com.ttl.tabletennis.scrape;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ttl.tabletennis.config.FeatureFlagCatalog;
import com.ttl.tabletennis.domain.IngestDlqEntry;
import com.ttl.tabletennis.repository.IngestDlqRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisStreamsConsumerTests {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final FeatureFlagCatalog featureFlags = mock(FeatureFlagCatalog.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final StreamOperations<String, Object, Object> streamOperations = mock(StreamOperations.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final IngestDlqRepository dlqRepository = mock(IngestDlqRepository.class);
    private RedisStreamsConsumer consumer;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        consumer = new RedisStreamsConsumer(
                featureFlags,
                Optional.of(redisTemplate),
                publisher,
                dlqRepository,
                objectMapper,
                new SimpleMeterRegistry(),
                "ttl",
                "ttl-app",
                "ttl-app-1",
                100
        );
    }

    @Test
    void shadowModeValidatesAndAcknowledgesWithoutDispatch() throws Exception {
        MapRecord<String, Object, Object> record = validRecord();

        consumer.handleRecord(record, "shadow");

        verify(publisher, never()).publishEvent(any());
        verify(streamOperations).acknowledge("ttl:scores", "ttl-app", record.getId());
        verify(dlqRepository, never()).save(any());
    }

    @Test
    void onModeDispatchesTypedEventBeforeAcknowledgement() throws Exception {
        MapRecord<String, Object, Object> record = validRecord();
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);

        consumer.handleRecord(record, "on");

        verify(publisher).publishEvent(eventCaptor.capture());
        IngestEvent<?> event = (IngestEvent<?>) eventCaptor.getValue();
        assertEquals(SourceId.SOFASCORE, event.source());
        assertTrue(event.payload() instanceof MirrorObservationPayload);
        verify(streamOperations).acknowledge("ttl:scores", "ttl-app", record.getId());
    }

    @Test
    void malformedRecordMovesToDlqThenAcknowledges() {
        Map<Object, Object> fields = new LinkedHashMap<>();
        fields.put("source_id", "SOFASCORE");
        fields.put("topic", "score.observed");
        fields.put("correlation_id", "corr-bad");
        fields.put("payload_json", "{}");
        MapRecord<String, Object, Object> record = MapRecord
                .create("ttl:scores", fields)
                .withId(RecordId.of("2-0"));
        ArgumentCaptor<IngestDlqEntry> dlqCaptor = ArgumentCaptor.forClass(IngestDlqEntry.class);

        consumer.handleRecord(record, "shadow");

        verify(dlqRepository).save(dlqCaptor.capture());
        assertEquals("score.observed", dlqCaptor.getValue().getTopic());
        assertEquals(SourceId.SOFASCORE, dlqCaptor.getValue().getSourceId());
        assertEquals("corr-bad", dlqCaptor.getValue().getCorrelationId());
        verify(streamOperations).acknowledge("ttl:scores", "ttl-app", record.getId());
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void failedDlqWriteLeavesRecordPendingForRetry() {
        MapRecord<String, Object, Object> record = MapRecord
                .create("ttl:scores", Map.<Object, Object>of("topic", "score.observed"))
                .withId(RecordId.of("3-0"));
        when(dlqRepository.save(any())).thenThrow(new RuntimeException("database unavailable"));

        consumer.handleRecord(record, "shadow");

        verify(streamOperations, never()).acknowledge(
                eq("ttl:scores"),
                eq("ttl-app"),
                any(RecordId[].class)
        );
    }

    private MapRecord<String, Object, Object> validRecord() throws Exception {
        MirrorObservationPayload payload = new MirrorObservationPayload(
                "tracked-1",
                "mirror-1",
                "Alpha",
                "Beta",
                "TT Elite",
                "LIVE",
                2,
                1,
                8,
                6,
                "Alpha",
                false,
                "{\"score\":\"8-6\"}"
        );
        Map<Object, Object> fields = new LinkedHashMap<>();
        fields.put("source_id", "SOFASCORE");
        fields.put("topic", "score.observed");
        fields.put("observed_at", Instant.parse("2026-07-29T12:00:00Z").toString());
        fields.put("confidence", "0.93");
        fields.put("correlation_id", "corr-1");
        fields.put("raw_payload_ref", "raw://mirror-1");
        fields.put("payload_type", MirrorObservationPayload.class.getName());
        fields.put("payload_json", objectMapper.writeValueAsString(payload));
        return MapRecord.create("ttl:scores", fields).withId(RecordId.of("1-0"));
    }
}
