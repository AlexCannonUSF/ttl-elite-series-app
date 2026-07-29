package com.ttl.tabletennis.service;

import com.ttl.tabletennis.config.FeatureFlagCatalog;
import com.ttl.tabletennis.dto.OpsIngestBusDto;
import com.ttl.tabletennis.dto.OpsIngestDlqDto;
import com.ttl.tabletennis.dto.OpsIngestDlqSourceDto;
import com.ttl.tabletennis.dto.OpsIngestDto;
import com.ttl.tabletennis.dto.OpsIngestPartitionDto;
import com.ttl.tabletennis.repository.IngestDlqRepository;
import com.ttl.tabletennis.scrape.SourceId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class OpsIngestService {

    private static final List<StreamFamily> STREAM_FAMILIES = List.of(
            new StreamFamily("odds", "Odds"),
            new StreamFamily("scores", "Scores"),
            new StreamFamily("results", "Results"),
            new StreamFamily("health", "Health"),
            new StreamFamily("identity", "Identity")
    );

    private final FeatureFlagCatalog featureFlagCatalog;
    private final IngestDlqRepository ingestDlqRepository;
    private final Optional<StringRedisTemplate> redisTemplate;
    private final String streamPrefix;
    private final long partitionLagWarning;
    private final long partitionLagCritical;

    public OpsIngestService(FeatureFlagCatalog featureFlagCatalog,
                            IngestDlqRepository ingestDlqRepository,
                            Optional<StringRedisTemplate> redisTemplate,
                            @Value("${ttl.ingestion.redis.streamPrefix:ttl}") String streamPrefix,
                            @Value("${ttl.ops.ingest.partitionLagWarning:1000}") long partitionLagWarning,
                            @Value("${ttl.ops.ingest.partitionLagCritical:10000}") long partitionLagCritical) {
        this.featureFlagCatalog = featureFlagCatalog;
        this.ingestDlqRepository = ingestDlqRepository;
        this.redisTemplate = redisTemplate == null ? Optional.empty() : redisTemplate;
        this.streamPrefix = normalizeStreamPrefix(streamPrefix);
        this.partitionLagWarning = Math.max(0L, partitionLagWarning);
        this.partitionLagCritical = Math.max(this.partitionLagWarning, partitionLagCritical);
    }

    @Transactional(readOnly = true)
    public OpsIngestDto snapshot() {
        Instant generatedAt = Instant.now();
        String mode = featureFlagCatalog.stateOf(FeatureFlagCatalog.REDIS_STREAMS_FLAG);
        boolean redisAvailable = redisAvailable();
        List<OpsIngestPartitionDto> partitions = STREAM_FAMILIES.stream()
                .map(family -> partition(family, redisAvailable))
                .toList();
        OpsIngestDlqDto dlq = dlq();

        return new OpsIngestDto(
                generatedAt,
                bus(mode, redisAvailable, partitions, dlq.totalDepth()),
                dlq,
                partitions
        );
    }

    private OpsIngestBusDto bus(String mode,
                                boolean redisAvailable,
                                List<OpsIngestPartitionDto> partitions,
                                long dlqDepth) {
        String activeBus = activeBus(mode);
        long maxLag = partitions.stream()
                .flatMap(partition -> partition.lag() == null ? Stream.empty() : Stream.of(partition.lag()))
                .mapToLong(Long::longValue)
                .max()
                .orElse(0L);
        boolean redisMode = "shadow".equals(mode) || "on".equals(mode);
        String status;
        String detail;

        if (!redisMode) {
            status = "OFF";
            detail = "In-process application event bus is active; Redis Streams is intentionally off.";
        } else if (!redisAvailable) {
            status = "on".equals(mode) ? "DOWN" : "DEGRADED";
            detail = "Redis Streams mode is enabled, but Redis is not reachable from this app instance.";
        } else if (dlqDepth > 0 || maxLag >= partitionLagCritical) {
            status = "DEGRADED";
            detail = "Redis is reachable, but queue pressure needs attention.";
        } else {
            status = "HEALTHY";
            detail = "Redis is reachable and ingestion lag is inside the configured guardrail.";
        }

        return new OpsIngestBusDto(
                mode,
                status,
                redisAvailable,
                activeBus,
                streamPrefix,
                detail
        );
    }

    private String activeBus(String mode) {
        if ("shadow".equals(mode)) {
            return "ApplicationEventIngestionBus + RedisStreamsBus shadow";
        }
        if ("on".equals(mode)) {
            return "RedisStreamsBus";
        }
        return "ApplicationEventIngestionBus";
    }

    private OpsIngestDlqDto dlq() {
        List<OpsIngestDlqSourceDto> sources = Arrays.stream(SourceId.values())
                .map(sourceId -> new OpsIngestDlqSourceDto(
                        sourceId.id(),
                        sourceId.tier().name(),
                        ingestDlqRepository.countBySourceId(sourceId)
                ))
                .filter(source -> source.depth() > 0L)
                .toList();
        return new OpsIngestDlqDto(ingestDlqRepository.count(), sources);
    }

    private OpsIngestPartitionDto partition(StreamFamily family, boolean redisAvailable) {
        String key = streamPrefix + ":" + family.suffix();
        if (!redisAvailable || redisTemplate.isEmpty()) {
            return unavailable(key, family.label(), "Redis is not reachable.");
        }

        try {
            StreamOperations<String, Object, Object> streamOps = redisTemplate.get().opsForStream();
            long length = nullToZero(streamOps.size(key));
            StreamInfo.XInfoStream info = length > 0L ? streamInfo(streamOps, key) : null;
            StreamInfo.XInfoGroups groups = length > 0L ? streamGroups(streamOps, key) : null;
            long groupCount = groups == null ? 0L : groups.size();
            long pending = pendingCount(groups);
            Long lag = lag(length, groups, pending);
            String status = partitionStatus(length, lag);
            String detail = groupCount == 0L && length > 0L
                    ? "No consumer group has claimed this stream yet; lag equals stream length."
                    : "Consumer group lag is inside the configured guardrail.";

            return new OpsIngestPartitionDto(
                    key,
                    family.label(),
                    status,
                    length,
                    groupCount,
                    pending,
                    lag,
                    info == null ? null : blankToNull(info.lastGeneratedId()),
                    detail
            );
        } catch (RuntimeException ex) {
            return unavailable(key, family.label(), ex.getMessage());
        }
    }

    private StreamInfo.XInfoStream streamInfo(StreamOperations<String, Object, Object> streamOps, String key) {
        try {
            return streamOps.info(key);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private StreamInfo.XInfoGroups streamGroups(StreamOperations<String, Object, Object> streamOps, String key) {
        try {
            return streamOps.groups(key);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private long pendingCount(StreamInfo.XInfoGroups groups) {
        if (groups == null || groups.isEmpty()) {
            return 0L;
        }
        return groups.stream()
                .mapToLong(group -> nullToZero(group.pendingCount()))
                .sum();
    }

    private Long lag(long length, StreamInfo.XInfoGroups groups, long pending) {
        if (groups == null || groups.isEmpty()) {
            return length;
        }
        long maxLag = groups.stream()
                .mapToLong(group -> Math.max(rawLag(group), nullToZero(group.pendingCount())))
                .max()
                .orElse(pending);
        return maxLag;
    }

    private long rawLag(StreamInfo.XInfoGroup group) {
        Object rawLag = group.getRaw().get("lag");
        if (rawLag instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        if (rawLag instanceof String text) {
            try {
                return Math.max(0L, Long.parseLong(text));
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private String partitionStatus(long length, Long lag) {
        if (length == 0L) {
            return "IDLE";
        }
        long lagValue = lag == null ? 0L : lag;
        if (lagValue >= partitionLagCritical) {
            return "HOT";
        }
        if (lagValue >= partitionLagWarning) {
            return "LAGGING";
        }
        return "HEALTHY";
    }

    private OpsIngestPartitionDto unavailable(String key, String family, String detail) {
        return new OpsIngestPartitionDto(
                key,
                family,
                "UNAVAILABLE",
                0L,
                0L,
                0L,
                null,
                null,
                detail == null || detail.isBlank() ? "Redis stream info is unavailable." : detail
        );
    }

    private boolean redisAvailable() {
        if (redisTemplate.isEmpty()) {
            return false;
        }
        try {
            RedisCallback<String> ping = connection -> connection.ping();
            return "PONG".equalsIgnoreCase(redisTemplate.get().execute(ping));
        } catch (RedisSystemException ex) {
            return false;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private String normalizeStreamPrefix(String value) {
        if (value == null || value.isBlank()) {
            return "ttl";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private Long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private record StreamFamily(String suffix, String label) {
    }
}
