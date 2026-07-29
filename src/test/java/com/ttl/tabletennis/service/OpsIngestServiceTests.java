package com.ttl.tabletennis.service;

import com.ttl.tabletennis.config.FeatureFlagCatalog;
import com.ttl.tabletennis.dto.OpsIngestDto;
import com.ttl.tabletennis.dto.OpsIngestPartitionDto;
import com.ttl.tabletennis.repository.IngestDlqRepository;
import com.ttl.tabletennis.scrape.SourceId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpsIngestServiceTests {

    @TempDir
    Path tempDir;

    @Test
    void offModeReportsApplicationEventBusWithoutRequiringRedis() throws IOException {
        IngestDlqRepository dlqRepository = mock(IngestDlqRepository.class);
        OpsIngestService service = new OpsIngestService(
                featureCatalog("off"),
                dlqRepository,
                Optional.empty(),
                "ttl",
                1000L,
                10000L
        );

        OpsIngestDto snapshot = service.snapshot();

        assertEquals("off", snapshot.bus().mode());
        assertEquals("OFF", snapshot.bus().status());
        assertEquals("ApplicationEventIngestionBus", snapshot.bus().activeBus());
        assertFalse(snapshot.bus().redisAvailable());
        assertEquals(0L, snapshot.dlq().totalDepth());
        assertTrue(snapshot.partitions().stream().allMatch(partition -> "UNAVAILABLE".equals(partition.status())));
    }

    @Test
    void onModeReportsRedisPartitionLagAndDlqPressure() throws IOException {
        IngestDlqRepository dlqRepository = mock(IngestDlqRepository.class);
        when(dlqRepository.count()).thenReturn(3L);
        when(dlqRepository.countBySourceId(SourceId.HR_MKT)).thenReturn(3L);

        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        StreamOperations<String, Object, Object> streamOperations = mock(StreamOperations.class);
        StreamInfo.XInfoStream streamInfo = mock(StreamInfo.XInfoStream.class);
        StreamInfo.XInfoGroups groups = mock(StreamInfo.XInfoGroups.class);
        StreamInfo.XInfoGroup group = mock(StreamInfo.XInfoGroup.class);

        when(redisTemplate.execute(any(RedisCallback.class))).thenReturn("PONG");
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.size(anyString())).thenReturn(0L);
        when(streamOperations.size("ttl:odds")).thenReturn(1200L);
        when(streamOperations.info("ttl:odds")).thenReturn(streamInfo);
        when(streamInfo.lastGeneratedId()).thenReturn("1715980000000-0");
        when(streamOperations.groups("ttl:odds")).thenReturn(groups);
        when(groups.isEmpty()).thenReturn(false);
        when(groups.size()).thenReturn(1);
        when(groups.stream()).thenAnswer(invocation -> Stream.of(group));
        when(group.pendingCount()).thenReturn(7L);
        when(group.getRaw()).thenReturn(Map.of("lag", 1100L));

        OpsIngestService service = new OpsIngestService(
                featureCatalog("on"),
                dlqRepository,
                Optional.of(redisTemplate),
                "ttl",
                1000L,
                10000L
        );

        OpsIngestDto snapshot = service.snapshot();
        OpsIngestPartitionDto odds = snapshot.partitions().stream()
                .filter(partition -> "ttl:odds".equals(partition.streamKey()))
                .findFirst()
                .orElseThrow();

        assertEquals("on", snapshot.bus().mode());
        assertEquals("DEGRADED", snapshot.bus().status());
        assertTrue(snapshot.bus().redisAvailable());
        assertEquals("RedisStreamsBus", snapshot.bus().activeBus());
        assertEquals(3L, snapshot.dlq().totalDepth());
        assertEquals(1, snapshot.dlq().sources().size());
        assertEquals("HR_MKT", snapshot.dlq().sources().get(0).sourceId());
        assertEquals("LAGGING", odds.status());
        assertEquals(1200L, odds.streamLength());
        assertEquals(1L, odds.consumerGroups());
        assertEquals(7L, odds.pendingCount());
        assertEquals(1100L, odds.lag());
        assertEquals("1715980000000-0", odds.lastGeneratedId());
    }

    @Test
    void onModeMarksBusDownWhenRedisPingFails() throws IOException {
        IngestDlqRepository dlqRepository = mock(IngestDlqRepository.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisCallback.class))).thenThrow(new IllegalStateException("redis down"));

        OpsIngestService service = new OpsIngestService(
                featureCatalog("on"),
                dlqRepository,
                Optional.of(redisTemplate),
                "ttl",
                1000L,
                10000L
        );

        OpsIngestDto snapshot = service.snapshot();

        assertEquals("DOWN", snapshot.bus().status());
        assertFalse(snapshot.bus().redisAvailable());
        assertNull(snapshot.partitions().get(0).lag());
    }

    @Test
    void emptyStreamStillReportsProvisionedConsumerGroup() throws IOException {
        IngestDlqRepository dlqRepository = mock(IngestDlqRepository.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        StreamOperations<String, Object, Object> streamOperations = mock(StreamOperations.class);
        StreamInfo.XInfoGroups groups = mock(StreamInfo.XInfoGroups.class);
        StreamInfo.XInfoGroup group = mock(StreamInfo.XInfoGroup.class);

        when(redisTemplate.execute(any(RedisCallback.class))).thenReturn("PONG");
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.size(anyString())).thenReturn(0L);
        when(streamOperations.groups(anyString())).thenReturn(groups);
        when(groups.isEmpty()).thenReturn(false);
        when(groups.size()).thenReturn(1);
        when(groups.stream()).thenAnswer(invocation -> Stream.of(group));
        when(group.pendingCount()).thenReturn(0L);
        when(group.getRaw()).thenReturn(Map.of("lag", 0L));

        OpsIngestService service = new OpsIngestService(
                featureCatalog("shadow"),
                dlqRepository,
                Optional.of(redisTemplate),
                "ttl",
                1000L,
                10000L
        );

        OpsIngestDto snapshot = service.snapshot();

        assertTrue(snapshot.partitions().stream().allMatch(partition -> partition.consumerGroups() == 1L));
        assertTrue(snapshot.partitions().stream().allMatch(partition -> partition.pendingCount() == 0L));
        assertTrue(snapshot.partitions().stream().allMatch(partition -> partition.lag() == 0L));
    }

    private FeatureFlagCatalog featureCatalog(String state) throws IOException {
        Path catalogPath = tempDir.resolve("features-" + state + ".yaml");
        Files.writeString(catalogPath, """
                schema_version: 1
                features:
                  "features.redis-streams":
                    owner: "Alex"
                    expires_on: "2026-07-15"
                    state: "%s"
                    description: "Switches ingestion events to Redis Streams."
                    allowed_states:
                      - "off"
                      - "shadow"
                      - "on"
                """.formatted(state));
        return new FeatureFlagCatalog(catalogPath.toString());
    }
}
