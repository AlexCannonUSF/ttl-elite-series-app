package com.ttl.tabletennis.scrape;

import com.ttl.tabletennis.model.MatchOdds;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class HardRockTargetedPoller implements FeedClient<MatchOdds> {

    static final SourceId SOURCE = SourceId.HR_TGT;
    private static final String TOPIC = "score.observed";
    private static final double DEFAULT_CONFIDENCE = 0.96;

    private final HardRockOddsScraper hardRockOddsScraper;
    private final IngestionBus ingestionBus;
    private final FeedHealthTracker healthTracker = new FeedHealthTracker();
    private final boolean enabled;

    public HardRockTargetedPoller(HardRockOddsScraper hardRockOddsScraper,
                                  IngestionBus ingestionBus,
                                  @Value("${ttl.hardrock.targeted.enabled:true}") boolean enabled) {
        this.hardRockOddsScraper = hardRockOddsScraper;
        this.ingestionBus = ingestionBus;
        this.enabled = enabled;
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

        List<String> eventIds = eventIds(ctx);
        if (eventIds.isEmpty()) {
            return List.of();
        }

        Instant startedAt = healthTracker.onPullStart();
        try {
            List<MatchOdds> rows = hardRockOddsScraper.fetchScoreboardByEventIds(eventIds);
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

    @Override
    public FeedHealth currentHealth() {
        return healthTracker.snapshot(source());
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(
                Capability.SCORES,
                Capability.MARKET_STATE,
                Capability.COMPLETION_SIGNAL,
                Capability.BOOKER_EVENT_ID
        );
    }

    public List<MatchOdds> fetchScoreboardByEventIds(Collection<String> externalEventIds) {
        if (!enabled) {
            return List.of();
        }
        return hardRockOddsScraper.fetchScoreboardByEventIds(externalEventIds);
    }

    private List<String> eventIds(PullContext ctx) {
        if (ctx == null || ctx.attributes() == null || ctx.attributes().isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        addDelimited(ids, ctx.attributes(), "externalEventId");
        addDelimited(ids, ctx.attributes(), "bookerEventId");
        addDelimited(ids, ctx.attributes(), "hrEventId");
        addDelimited(ids, ctx.attributes(), "eventId");
        addDelimited(ids, ctx.attributes(), "eventIds");
        addDelimited(ids, ctx.attributes(), "externalEventIds");
        addDelimited(ids, ctx.attributes(), "sourceFeedEventId");
        return ids.stream().toList();
    }

    private void addDelimited(LinkedHashSet<String> ids, Map<String, String> attributes, String key) {
        String value = attributes.get(key);
        if (!StringUtils.hasText(value)) {
            return;
        }
        for (String raw : value.split("[,\\s]+")) {
            if (StringUtils.hasText(raw)) {
                ids.add(raw.trim());
            }
        }
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
