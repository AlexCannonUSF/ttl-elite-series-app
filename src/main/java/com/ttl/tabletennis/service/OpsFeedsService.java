package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.FeedHealthSample;
import com.ttl.tabletennis.dto.OpsFeedStatusDto;
import com.ttl.tabletennis.dto.OpsFeedsDto;
import com.ttl.tabletennis.dto.OpsFeedsSummaryDto;
import com.ttl.tabletennis.repository.FeedHealthSampleRepository;
import com.ttl.tabletennis.repository.IngestDlqRepository;
import com.ttl.tabletennis.scrape.FeedClient;
import com.ttl.tabletennis.scrape.FeedHealth;
import com.ttl.tabletennis.scrape.SourceId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Service
public class OpsFeedsService {

    private static final long LIVE_TICK_WINDOW_SECONDS = 15L;
    private static final long DEGRADED_STALENESS_SECONDS = 60L;
    private static final long DOWN_STALENESS_SECONDS = 300L;
    private static final double DEGRADED_SUCCESS_RATE = 0.85;
    private static final double DOWN_SUCCESS_RATE = 0.40;

    private final List<FeedClient<?>> feedClients;
    private final FeedHealthService feedHealthService;
    private final FeedHealthSampleRepository feedHealthSampleRepository;
    private final IngestDlqRepository ingestDlqRepository;

    public OpsFeedsService(List<FeedClient<?>> feedClients,
                           FeedHealthService feedHealthService,
                           FeedHealthSampleRepository feedHealthSampleRepository,
                           IngestDlqRepository ingestDlqRepository) {
        this.feedClients = feedClients == null ? List.of() : List.copyOf(feedClients);
        this.feedHealthService = feedHealthService;
        this.feedHealthSampleRepository = feedHealthSampleRepository;
        this.ingestDlqRepository = ingestDlqRepository;
    }

    @Transactional(readOnly = true)
    public OpsFeedsDto snapshot() {
        Instant generatedAt = Instant.now();
        Map<SourceId, FeedClient<?>> feedClientsBySource = feedClients.stream()
                .sorted(Comparator.comparing(feedClient -> feedClient.source().id()))
                .collect(LinkedHashMap::new, (map, client) -> map.put(client.source(), client), Map::putAll);

        List<OpsFeedStatusDto> feeds = feedHealthService.currentFeeds().stream()
                .map(health -> toDto(health, feedClientsBySource.get(health.source()), generatedAt))
                .toList();

        OpsFeedsSummaryDto summary = new OpsFeedsSummaryDto(
                feeds.size(),
                countByStatus(feeds, "HEALTHY"),
                countByStatus(feeds, "DEGRADED"),
                countByStatus(feeds, "DOWN"),
                countByStatus(feeds, "IDLE"),
                feeds.stream().mapToLong(OpsFeedStatusDto::dlqDepth).sum()
        );
        return new OpsFeedsDto(generatedAt, summary, feeds);
    }

    private OpsFeedStatusDto toDto(FeedHealth health, FeedClient<?> feedClient, Instant generatedAt) {
        long dlqDepth = ingestDlqRepository.countBySourceId(health.source());
        String status = classify(health, dlqDepth);

        return new OpsFeedStatusDto(
                health.source().id(),
                health.source().tier().name(),
                capabilities(feedClient),
                status,
                isLiveTick(health, generatedAt),
                successRateOrNull(health, status),
                metricOrNull(health.rollingP50LatencyMs()),
                metricOrNull(health.rollingP95LatencyMs()),
                stalenessOrNull(health, status),
                health.inFlight(),
                blankToNull(health.backoffState()),
                blankToNull(health.lastError()),
                dlqDepth,
                health.lastSuccess(),
                health.lastFailure(),
                latestSampleAt(health.source())
        );
    }

    private List<String> capabilities(FeedClient<?> feedClient) {
        if (feedClient == null) {
            return List.of();
        }
        return feedClient.capabilities().stream()
                .map(Enum::name)
                .sorted()
                .toList();
    }

    private Double successRateOrNull(FeedHealth health, String status) {
        if ("IDLE".equals(status)) {
            return null;
        }
        return health.rollingSuccessRate5m();
    }

    private Double metricOrNull(double value) {
        return value < 0.0 ? null : value;
    }

    private Long stalenessOrNull(FeedHealth health, String status) {
        if ("IDLE".equals(status) || health.stalenessSeconds() < 0L) {
            return null;
        }
        return health.stalenessSeconds();
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private Instant latestSampleAt(SourceId sourceId) {
        return feedHealthSampleRepository.findTopBySourceIdOrderByObservedAtDesc(sourceId)
                .map(FeedHealthSample::getObservedAt)
                .map(localDateTime -> localDateTime.toInstant(ZoneOffset.UTC))
                .orElse(null);
    }

    private boolean isLiveTick(FeedHealth health, Instant generatedAt) {
        if (health.lastSuccess() == null) {
            return false;
        }
        long ageSeconds = Math.max(0L, Duration.between(health.lastSuccess(), generatedAt).toSeconds());
        return ageSeconds <= LIVE_TICK_WINDOW_SECONDS;
    }

    private String classify(FeedHealth health, long dlqDepth) {
        boolean neverSeen = health.lastSuccess() == null && health.lastFailure() == null;
        if (neverSeen) {
            return "IDLE";
        }
        if (health.lastSuccess() == null
                || health.stalenessSeconds() > DOWN_STALENESS_SECONDS
                || health.rollingSuccessRate5m() < DOWN_SUCCESS_RATE) {
            return "DOWN";
        }
        if (dlqDepth > 0
                || health.stalenessSeconds() > DEGRADED_STALENESS_SECONDS
                || health.rollingSuccessRate5m() < DEGRADED_SUCCESS_RATE
                || hasActiveBackoff(health.backoffState())
                || hasText(health.lastError())) {
            return "DEGRADED";
        }
        return "HEALTHY";
    }

    private boolean hasActiveBackoff(String backoffState) {
        return hasText(backoffState) && !"IDLE".equalsIgnoreCase(backoffState.trim());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private long countByStatus(List<OpsFeedStatusDto> feeds, String status) {
        return feeds.stream()
                .filter(feed -> status.equals(feed.status()))
                .count();
    }
}
