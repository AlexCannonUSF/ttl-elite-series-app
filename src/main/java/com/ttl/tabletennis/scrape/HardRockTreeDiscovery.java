package com.ttl.tabletennis.scrape;

import com.ttl.tabletennis.model.MatchOdds;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Component
public class HardRockTreeDiscovery implements FeedClient<MatchOdds> {

    static final SourceId SOURCE = SourceId.HR_TREE;
    static final String TOPIC = "identity.updated";
    private static final double DEFAULT_CONFIDENCE = 0.70;

    private final HardRockOddsScraper hardRockOddsScraper;
    private final IngestionBus ingestionBus;
    private final FeedHealthTracker healthTracker = new FeedHealthTracker();
    private final boolean enabled;
    private final boolean scheduledEnabled;

    public HardRockTreeDiscovery(HardRockOddsScraper hardRockOddsScraper,
                                 IngestionBus ingestionBus,
                                 @Value("${ttl.hardrock.treeDiscovery.enabled:false}") boolean enabled,
                                 @Value("${ttl.hardrock.treeDiscovery.scheduledEnabled:false}") boolean scheduledEnabled) {
        this.hardRockOddsScraper = hardRockOddsScraper;
        this.ingestionBus = ingestionBus;
        this.enabled = enabled;
        this.scheduledEnabled = scheduledEnabled;
    }

    @Override
    public SourceId source() {
        return SOURCE;
    }

    @Override
    public List<IngestEvent<MatchOdds>> pullOnce(PullContext ctx) {
        if (!enabled) {
            return List.of();
        }

        Instant startedAt = healthTracker.onPullStart();
        try {
            List<MatchOdds> rows = hardRockOddsScraper.discoverPublicTreeEvents();
            String correlationId = ctx == null ? "" : ctx.correlationId();
            List<IngestEvent<MatchOdds>> events = rows.stream()
                    .map(row -> new IngestEvent<>(
                            source(),
                            TOPIC,
                            observedAt(row),
                            confidence(row),
                            correlationId,
                            "",
                            row
                    ))
                    .toList();
            ingestionBus.publishAll(events);
            healthTracker.onPullSuccess(startedAt);
            return events;
        } catch (RuntimeException exception) {
            healthTracker.onPullFailure(startedAt, exception);
            throw exception;
        }
    }

    @Scheduled(fixedDelayString = "${ttl.hardrock.treeDiscovery.fixedDelayMs:60000}")
    public void scheduledDiscover() {
        if (!enabled || !scheduledEnabled) {
            return;
        }
        pullOnce(PullContext.now("hr-tree-scheduled"));
    }

    @Override
    public FeedHealth currentHealth() {
        return healthTracker.snapshot(source());
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(
                Capability.MARKET_STATE,
                Capability.BOOKER_EVENT_ID,
                Capability.SCORES
        );
    }

    private static Instant observedAt(MatchOdds row) {
        if (row == null || row.getTimestamp() <= 0L) {
            return Instant.now();
        }
        return Instant.ofEpochMilli(row.getTimestamp());
    }

    private static double confidence(MatchOdds row) {
        if (row == null || row.getSourceConfidence() <= 0.0) {
            return DEFAULT_CONFIDENCE;
        }
        return row.getSourceConfidence();
    }
}
