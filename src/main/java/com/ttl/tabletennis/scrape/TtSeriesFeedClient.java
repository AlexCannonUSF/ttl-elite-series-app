package com.ttl.tabletennis.scrape;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;

@Component
public class TtSeriesFeedClient implements FeedClient<TtSeriesScraper.OfficialLedgerMatch> {

    static final SourceId SOURCE = SourceId.TTS_POST;
    private static final double DEFAULT_CONFIDENCE = 1.0;

    private final TtSeriesScraper ttSeriesScraper;
    private final IngestionBus ingestionBus;
    private final FeedHealthTracker healthTracker = new FeedHealthTracker();

    public TtSeriesFeedClient(TtSeriesScraper ttSeriesScraper,
                              IngestionBus ingestionBus) {
        this.ttSeriesScraper = ttSeriesScraper;
        this.ingestionBus = ingestionBus;
    }

    @Override
    public SourceId source() {
        return SOURCE;
    }

    @Override
    public List<IngestEvent<TtSeriesScraper.OfficialLedgerMatch>> pullOnce(PullContext ctx) {
        String player1Name = attribute(ctx, "player1Name");
        String player2Name = attribute(ctx, "player2Name");
        if (!StringUtils.hasText(player1Name) || !StringUtils.hasText(player2Name)) {
            return List.of();
        }

        int limit = parseLimit(attribute(ctx, "limit"), 10);

        Instant startedAt = healthTracker.onPullStart();
        try {
            List<TtSeriesScraper.OfficialLedgerMatch> matches = ttSeriesScraper.lookupOfficialMatchesForPair(
                    player1Name,
                    player2Name,
                    limit
            );
            List<IngestEvent<TtSeriesScraper.OfficialLedgerMatch>> events = matches.stream()
                    .map(match -> new IngestEvent<>(
                            source(),
                            "result.confirmed",
                            Instant.now(),
                            DEFAULT_CONFIDENCE,
                            ctx == null ? "" : ctx.correlationId(),
                            "",
                            match
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
        return Set.of(Capability.RESULTS);
    }

    public int refreshRecentOfficialResultsLegacy(int pages) throws IOException, InterruptedException {
        return ttSeriesScraper.refreshRecentOfficialResults(pages);
    }

    public List<TtSeriesScraper.OfficialLedgerMatch> lookupOfficialMatchesForPairLegacy(String player1Name,
                                                                                         String player2Name,
                                                                                         int limit) {
        return ttSeriesScraper.lookupOfficialMatchesForPair(player1Name, player2Name, limit);
    }

    private static String attribute(PullContext ctx, String key) {
        if (ctx == null || ctx.attributes() == null || !StringUtils.hasText(key)) {
            return "";
        }
        String value = ctx.attributes().get(key);
        return value == null ? "" : value.trim();
    }

    private static int parseLimit(String rawLimit, int fallback) {
        if (!StringUtils.hasText(rawLimit)) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(rawLimit.trim()));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
