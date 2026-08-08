package com.ttl.tabletennis.service;

import com.ttl.tabletennis.config.FeatureFlagCatalog;
import com.ttl.tabletennis.dto.OpsIngestBusDto;
import com.ttl.tabletennis.dto.OpsIngestDlqDto;
import com.ttl.tabletennis.dto.OpsIngestDlqSourceDto;
import com.ttl.tabletennis.dto.OpsIngestDto;
import com.ttl.tabletennis.dto.OpsIngestPartitionDto;
import com.ttl.tabletennis.dto.OpsIngestTelemetryDto;
import com.ttl.tabletennis.repository.IngestDlqRepository;
import com.ttl.tabletennis.scrape.SourceId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.lang.management.ManagementFactory;
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
    private final MeterRegistry meterRegistry;
    private final java.util.concurrent.atomic.AtomicReference<SoakBaseline> soakBaseline =
            new java.util.concurrent.atomic.AtomicReference<>();

    @Value("${ttl.ingestion.redis.consumer.group:ttl-app}")
    private String consumerGroupName = "ttl-app";

    public OpsIngestService(FeatureFlagCatalog featureFlagCatalog,
                            IngestDlqRepository ingestDlqRepository,
                            Optional<StringRedisTemplate> redisTemplate,
                            @Value("${ttl.ingestion.redis.streamPrefix:ttl}") String streamPrefix,
                            @Value("${ttl.ops.ingest.partitionLagWarning:1000}") long partitionLagWarning,
                            @Value("${ttl.ops.ingest.partitionLagCritical:10000}") long partitionLagCritical) {
        this(featureFlagCatalog, ingestDlqRepository, redisTemplate, streamPrefix,
                partitionLagWarning, partitionLagCritical, null);
    }

    @Autowired
    public OpsIngestService(FeatureFlagCatalog featureFlagCatalog,
                            IngestDlqRepository ingestDlqRepository,
                            Optional<StringRedisTemplate> redisTemplate,
                            @Value("${ttl.ingestion.redis.streamPrefix:ttl}") String streamPrefix,
                            @Value("${ttl.ops.ingest.partitionLagWarning:1000}") long partitionLagWarning,
                            @Value("${ttl.ops.ingest.partitionLagCritical:10000}") long partitionLagCritical,
                            MeterRegistry meterRegistry) {
        this.featureFlagCatalog = featureFlagCatalog;
        this.ingestDlqRepository = ingestDlqRepository;
        this.redisTemplate = redisTemplate == null ? Optional.empty() : redisTemplate;
        this.streamPrefix = normalizeStreamPrefix(streamPrefix);
        this.partitionLagWarning = Math.max(0L, partitionLagWarning);
        this.partitionLagCritical = Math.max(this.partitionLagWarning, partitionLagCritical);
        this.meterRegistry = meterRegistry;
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
                telemetry(mode, redisAvailable, partitions, generatedAt),
                dlq,
                partitions
        );
    }

    private OpsIngestTelemetryDto telemetry(String mode,
                                            boolean redisAvailable,
                                            List<OpsIngestPartitionDto> partitions,
                                            Instant generatedAt) {
        long published = counter("ttl.ingest.redis.publisher.records", "published");
        long decoded = counter("ttl.ingest.redis.consumer.records", "decoded");
        long validated = counter("ttl.ingest.redis.consumer.records", "validated");
        long dispatched = counter("ttl.ingest.redis.consumer.records", "dispatched");
        long acknowledged = counter("ttl.ingest.redis.consumer.records", "acknowledged");
        long rejected = counter("ttl.ingest.redis.consumer.records", "rejected");
        long dlq = counter("ttl.ingest.redis.consumer.records", "dlq");
        long pollFailures = counter("ttl.ingest.redis.consumer.records", "poll_failure");
        boolean redisMode = "shadow".equals(mode) || "on".equals(mode);
        boolean fullTrafficCoverage = redisMode && redisAvailable && partitions.stream()
                .allMatch(partition -> partition.streamLength() > 0 && partition.consumerGroups() > 0);
        if (!fullTrafficCoverage) {
            soakBaseline.set(null);
        } else {
            soakBaseline.compareAndSet(null, new SoakBaseline(
                    generatedAt, published, acknowledged, rejected, dlq, pollFailures));
        }
        SoakBaseline baseline = soakBaseline.get();
        long parityDelta = baseline == null
                ? published - acknowledged
                : (published - baseline.published()) - (acknowledged - baseline.acknowledged());
        long rejectedDuringSoak = baseline == null ? rejected : rejected - baseline.rejected();
        long dlqDuringSoak = baseline == null ? dlq : dlq - baseline.dlq();
        long pollFailuresDuringSoak = baseline == null
                ? pollFailures
                : pollFailures - baseline.pollFailures();
        Instant startedAt = baseline == null ? null : baseline.startedAt();
        Long soakSeconds = startedAt == null ? null
                : Math.max(0L, java.time.Duration.between(startedAt, generatedAt).toSeconds());
        String soakStatus;
        if (!redisMode) soakStatus = "NOT_APPLICABLE";
        else if (!redisAvailable) soakStatus = "REDIS_UNAVAILABLE";
        else if (!fullTrafficCoverage) soakStatus = "WAITING_FOR_FULL_TRAFFIC";
        else if (parityDelta != 0) soakStatus = "PARITY_MISMATCH";
        else if (rejectedDuringSoak > 0 || dlqDuringSoak > 0 || pollFailuresDuringSoak > 0) {
            soakStatus = "FAILED_PROCESS_LIFETIME_CHECKS";
        }
        else if (soakSeconds != null && soakSeconds >= 604_800L) soakStatus = "PASSED_7_DAY_SOAK";
        else soakStatus = "SOAKING";
        long redeliveries = partitions.stream().mapToLong(OpsIngestPartitionDto::redeliveryCount).sum();
        double uptimeMinutes = Math.max(1.0 / 60.0,
                ManagementFactory.getRuntimeMXBean().getUptime() / 60_000.0);
        return new OpsIngestTelemetryDto(
                published, decoded, validated, dispatched, acknowledged, rejected, dlq, pollFailures,
                parityDelta,
                redeliveries,
                acknowledged / uptimeMinutes,
                gaugeInstant("ttl.ingest.redis.consumer.heartbeat.epoch_ms"),
                gaugeInstant("ttl.ingest.redis.consumer.last_processed.epoch_ms"),
                gaugeLong("ttl.ingest.redis.consumer.latest_event.age_ms"),
                fullTrafficCoverage,
                soakSeconds,
                soakStatus);
    }

    private long counter(String name, String outcome) {
        if (meterRegistry == null) return 0L;
        Counter counter = meterRegistry.find(name).tag("outcome", outcome).counter();
        return counter == null ? 0L : Math.round(counter.count());
    }

    private Instant gaugeInstant(String name) {
        Long epochMs = gaugeLong(name);
        return epochMs == null || epochMs <= 0L ? null : Instant.ofEpochMilli(epochMs);
    }

    private Long gaugeLong(String name) {
        if (meterRegistry == null) return null;
        var gauge = meterRegistry.find(name).gauge();
        if (gauge == null || !Double.isFinite(gauge.value())) return null;
        return Math.max(0L, Math.round(gauge.value()));
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
                partitionLagWarning,
                partitionLagCritical,
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
            // XGROUP CREATE ... MKSTREAM creates a legitimate group on a
            // zero-length stream. Query groups regardless of XLEN so the ops
            // surface reports that readiness instead of a false zero.
            StreamInfo.XInfoGroups groups = streamGroups(streamOps, key);
            long groupCount = groups == null ? 0L : groups.size();
            long pending = pendingCount(groups);
            PendingStats pendingStats = pendingStats(streamOps, key, pending);
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
                    pendingStats.oldestAgeSeconds(),
                    pendingStats.redeliveryCount(),
                    lag,
                    info == null ? null : blankToNull(info.lastGeneratedId()),
                    detail
            );
        } catch (RuntimeException ex) {
            return unavailable(key, family.label(), ex.getMessage());
        }
    }

    private PendingStats pendingStats(StreamOperations<String, Object, Object> streamOps,
                                      String key,
                                      long pendingCount) {
        if (pendingCount <= 0L) return new PendingStats(null, 0L);
        try {
            long sampleSize = Math.min(1_000L, pendingCount);
            PendingMessages messages = streamOps.pending(
                    key,
                    consumerGroupName,
                    Range.unbounded(),
                    sampleSize
            );
            long oldestSeconds = 0L;
            long redeliveries = 0L;
            for (PendingMessage message : messages) {
                oldestSeconds = Math.max(oldestSeconds,
                        Math.max(0L, message.getElapsedTimeSinceLastDelivery().toSeconds()));
                redeliveries += Math.max(0L, message.getTotalDeliveryCount() - 1L);
            }
            return new PendingStats(oldestSeconds, redeliveries);
        } catch (RuntimeException ignored) {
            return new PendingStats(null, 0L);
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

    private record PendingStats(Long oldestAgeSeconds, long redeliveryCount) {
    }

    private record SoakBaseline(Instant startedAt,
                                long published,
                                long acknowledged,
                                long rejected,
                                long dlq,
                                long pollFailures) {
    }
}
