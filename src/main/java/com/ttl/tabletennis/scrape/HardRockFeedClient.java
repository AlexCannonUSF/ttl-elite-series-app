package com.ttl.tabletennis.scrape;

import com.ttl.tabletennis.model.MatchOdds;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

@Component
public class HardRockFeedClient implements FeedClient<MatchOdds> {

    static final SourceId SOURCE = SourceId.HR_MKT;
    private static final double DEFAULT_CONFIDENCE = 0.90;

    private final HardRockOddsScraper hardRockOddsScraper;
    private final IngestionBus ingestionBus;
    private final FeedHealthTracker healthTracker = new FeedHealthTracker();

    public HardRockFeedClient(HardRockOddsScraper hardRockOddsScraper,
                              IngestionBus ingestionBus) {
        this.hardRockOddsScraper = hardRockOddsScraper;
        this.ingestionBus = ingestionBus;
    }

    @Override
    public SourceId source() {
        return SOURCE;
    }

    @Override
    public List<IngestEvent<MatchOdds>> pullOnce(PullContext ctx) {
        Instant startedAt = healthTracker.onPullStart();
        try {
            List<MatchOdds> rows = hardRockOddsScraper.fetch();
            List<IngestEvent<MatchOdds>> events = rows.stream()
                    .map(row -> new IngestEvent<>(
                            source(),
                            "odds.updated",
                            observedAt(row),
                            confidence(row),
                            ctx == null ? "" : ctx.correlationId(),
                            "",
                            row
                    ))
                    .toList();
            events.forEach(ingestionBus::publish);
            healthTracker.onPullSuccess(startedAt);
            return events;
        } catch (RuntimeException exception) {
            healthTracker.onPullFailure(startedAt, exception);
            throw exception;
        }
    }

    @Override
    public FeedHealth currentHealth() {
        return healthTracker.snapshot(source());
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(
                Capability.ODDS,
                Capability.SCORES,
                Capability.MARKET_STATE,
                Capability.BOOKER_EVENT_ID
        );
    }

    public List<MatchOdds> fetchLegacy() {
        return hardRockOddsScraper.fetch();
    }

    public List<MatchOdds> fetchScoreboardLegacy() {
        return hardRockOddsScraper.fetchScoreboard();
    }

    public List<MatchOdds> fetchScoreboardByEventIdsLegacy(Collection<String> externalEventIds) {
        return hardRockOddsScraper.fetchScoreboardByEventIds(externalEventIds);
    }

    private static Instant observedAt(MatchOdds row) {
        if (row == null || row.getTimestamp() <= 0L) {
            return Instant.now();
        }
        return Instant.ofEpochMilli(row.getTimestamp());
    }

    private static double confidence(MatchOdds row) {
        if (row == null) {
            return DEFAULT_CONFIDENCE;
        }
        double sourceConfidence = row.getSourceConfidence();
        return sourceConfidence > 0.0 ? sourceConfidence : DEFAULT_CONFIDENCE;
    }
}
