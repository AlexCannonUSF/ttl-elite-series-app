package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.FeedHealthSample;
import com.ttl.tabletennis.repository.FeedHealthSampleRepository;
import com.ttl.tabletennis.scrape.FeedClient;
import com.ttl.tabletennis.scrape.FeedHealth;
import com.ttl.tabletennis.scrape.IngestEvent;
import com.ttl.tabletennis.scrape.IngestionBus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

@Service
public class FeedHealthService {

    static final String TOPIC = "feed.health";

    private final List<FeedClient<?>> feedClients;
    private final FeedHealthSampleRepository feedHealthSampleRepository;
    private final IngestionBus ingestionBus;

    public FeedHealthService(List<FeedClient<?>> feedClients,
                             FeedHealthSampleRepository feedHealthSampleRepository,
                             IngestionBus ingestionBus) {
        this.feedClients = feedClients == null ? List.of() : List.copyOf(feedClients);
        this.feedHealthSampleRepository = feedHealthSampleRepository;
        this.ingestionBus = ingestionBus;
    }

    public List<FeedHealth> currentFeeds() {
        return feedClients.stream()
                .map(FeedClient::currentHealth)
                .sorted(Comparator.comparing(health -> health.source().id()))
                .toList();
    }

    @Scheduled(fixedDelayString = "${ttl.feed.health.emitFixedDelayMs:1000}")
    public void scheduledEmitFeedHealthEvents() {
        emitFeedHealthEvents(Instant.now());
    }

    @Scheduled(fixedDelayString = "${ttl.feed.health.persistFixedDelayMs:30000}")
    @Transactional
    public void scheduledPersistFeedHealthSamples() {
        persistFeedHealthSamples(Instant.now());
    }

    void emitFeedHealthEvents(Instant observedAt) {
        Instant safeObservedAt = observedAt == null ? Instant.now() : observedAt;
        for (FeedHealth health : currentFeeds()) {
            ingestionBus.publish(new IngestEvent<>(
                    health.source(),
                    TOPIC,
                    safeObservedAt,
                    1.0,
                    "",
                    "",
                    health
            ));
        }
    }

    @Transactional
    void persistFeedHealthSamples(Instant observedAt) {
        List<FeedHealthSample> samples = currentFeeds().stream()
                .map(health -> toSample(health, observedAt))
                .toList();
        if (!samples.isEmpty()) {
            feedHealthSampleRepository.saveAll(samples);
        }
    }

    private FeedHealthSample toSample(FeedHealth health, Instant observedAt) {
        FeedHealthSample sample = new FeedHealthSample();
        sample.setSourceId(health.source());
        sample.setObservedAt(LocalDateTime.ofInstant(observedAt == null ? Instant.now() : observedAt, ZoneOffset.UTC));
        sample.setRollingSuccessRate5m(health.rollingSuccessRate5m());
        sample.setRollingP50LatencyMs(nullableMetric(health.rollingP50LatencyMs()));
        sample.setRollingP95LatencyMs(nullableMetric(health.rollingP95LatencyMs()));
        sample.setInFlight(health.inFlight());
        sample.setBackoffState(blankToNull(health.backoffState()));
        sample.setLastError(blankToNull(health.lastError()));
        return sample;
    }

    private Double nullableMetric(double value) {
        return value < 0.0 ? null : value;
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
