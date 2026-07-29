package com.ttl.tabletennis.scrape;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisStreamsBusTests {

    @Test
    void publishWritesIngestEventToTopicFamilyStream() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOperations = mock(StreamOperations.class);
        IngestMetricsRecorder metricsRecorder = mock(IngestMetricsRecorder.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.add(eq("ttl:odds"), anyMap())).thenReturn(RecordId.of("1-0"));

        RedisStreamsBus bus = new RedisStreamsBus(
                redisTemplate,
                new ObjectMapper().findAndRegisterModules(),
                metricsRecorder,
                "ttl"
        );
        IngestEvent<TestPayload> event = new IngestEvent<>(
                SourceId.HR_MKT,
                "odds.updated",
                Instant.parse("2026-05-18T01:00:00Z"),
                0.91,
                "corr-redis-1",
                "raw://payload-1",
                new TestPayload("Alpha", "Beta")
        );

        bus.publish(event);

        verify(streamOperations).add(eq("ttl:odds"), anyMap());
        verify(metricsRecorder).recordPublished(event);
        Map<String, String> fields = bus.fieldsFor(event);
        assertEquals("HR_MKT", fields.get("source_id"));
        assertEquals("odds.updated", fields.get("topic"));
        assertEquals("2026-05-18T01:00:00Z", fields.get("observed_at"));
        assertEquals("0.91", fields.get("confidence"));
        assertEquals("corr-redis-1", fields.get("correlation_id"));
        assertEquals("raw://payload-1", fields.get("raw_payload_ref"));
        assertTrue(fields.get("payload_json").contains("\"player1\":\"Alpha\""));
    }

    @Test
    void streamKeyMapsKnownTopicFamilies() {
        RedisStreamsBus bus = new RedisStreamsBus(
                mock(StringRedisTemplate.class),
                new ObjectMapper(),
                mock(IngestMetricsRecorder.class),
                "ttl"
        );

        assertEquals("ttl:odds", bus.streamKey(event("odds.updated")));
        assertEquals("ttl:scores", bus.streamKey(event("score.observed")));
        assertEquals("ttl:scores", bus.streamKey(event("stream.frame")));
        assertEquals("ttl:results", bus.streamKey(event("result.confirmed")));
        assertEquals("ttl:health", bus.streamKey(event("feed.health")));
        assertEquals("ttl:identity", bus.streamKey(event("identity.updated")));
        assertEquals("ttl:identity", bus.streamKey(event("ranking.updated")));
    }

    @Test
    void publishTrimsStreamEveryNthCallToBoundShadowGrowth() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOperations = mock(StreamOperations.class);
        IngestMetricsRecorder metricsRecorder = mock(IngestMetricsRecorder.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.add(eq("ttl:health"), anyMap())).thenReturn(RecordId.of("1-0"));

        // Custom small max so we don't have to publish thousands of events
        // in a unit test. Trim cadence is hard-wired to every 50 publishes
        // (constant in RedisStreamsBus); below 50 → no trim, at/after 50 → trim.
        RedisStreamsBus bus = new RedisStreamsBus(
                redisTemplate,
                new ObjectMapper().findAndRegisterModules(),
                metricsRecorder,
                "ttl",
                1_000L
        );

        // 49 publishes — should NOT trigger any trim yet.
        for (int i = 0; i < 49; i++) {
            bus.publish(event("feed.health"));
        }
        verify(streamOperations, never()).trim(eq("ttl:health"), anyLong(), anyBoolean());

        // 50th publish — trim should fire exactly once with approximate=true.
        bus.publish(event("feed.health"));
        verify(streamOperations, atLeastOnce()).trim(eq("ttl:health"), eq(1_000L), eq(true));
    }

    @Test
    void publishSurvivesTrimFailureSoUpstreamsKeepFlowing() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOperations = mock(StreamOperations.class);
        IngestMetricsRecorder metricsRecorder = mock(IngestMetricsRecorder.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.add(eq("ttl:health"), anyMap())).thenReturn(RecordId.of("1-0"));
        // Trim throws — publisher must not propagate.
        when(streamOperations.trim(eq("ttl:health"), anyLong(), anyBoolean()))
                .thenThrow(new RuntimeException("redis down"));

        RedisStreamsBus bus = new RedisStreamsBus(
                redisTemplate,
                new ObjectMapper().findAndRegisterModules(),
                metricsRecorder,
                "ttl",
                1_000L
        );
        for (int i = 0; i < 50; i++) {
            bus.publish(event("feed.health"));
        }
        // All 50 publishes recorded successfully despite the trim throwing.
        verify(streamOperations, atLeastOnce()).add(eq("ttl:health"), anyMap());
        verify(metricsRecorder, atLeastOnce()).recordPublished(org.mockito.ArgumentMatchers.any());
    }

    private static IngestEvent<String> event(String topic) {
        return new IngestEvent<>(
                SourceId.INTERNAL_DB,
                topic,
                Instant.parse("2026-05-18T01:00:00Z"),
                1.0,
                "",
                "",
                "payload"
        );
    }

    private record TestPayload(String player1, String player2) {
    }
}
