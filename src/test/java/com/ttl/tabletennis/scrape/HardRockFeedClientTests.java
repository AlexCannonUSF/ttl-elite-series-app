package com.ttl.tabletennis.scrape;

import com.ttl.tabletennis.model.MatchOdds;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HardRockFeedClientTests {

    @Test
    void pullOnceWrapsLegacyRowsWithoutChangingPayloads() {
        HardRockOddsScraper scraper = mock(HardRockOddsScraper.class);
        IngestionBus ingestionBus = mock(IngestionBus.class);
        HardRockFeedClient client = new HardRockFeedClient(scraper, ingestionBus);

        MatchOdds row = new MatchOdds("Alpha One", "Beta Two", 1.82, 2.06);
        row.setSourceConfidence(0.97);
        when(scraper.fetch()).thenReturn(List.of(row));

        FeedClient.PullContext ctx = new FeedClient.PullContext(
                Instant.parse("2026-04-16T12:00:00Z"),
                "corr-hr-1",
                null
        );

        List<IngestEvent<MatchOdds>> events = client.pullOnce(ctx);

        assertEquals(1, events.size());
        assertEquals(HardRockFeedClient.SOURCE, events.get(0).source());
        assertEquals("odds.updated", events.get(0).topic());
        assertEquals("corr-hr-1", events.get(0).correlationId());
        assertEquals(0.97, events.get(0).confidence(), 0.0001);
        assertSame(row, events.get(0).payload());
        assertEquals(1.0, client.currentHealth().rollingSuccessRate5m(), 0.0001);
        assertEquals("IDLE", client.currentHealth().backoffState());
        assertTrue(client.capabilities().contains(FeedClient.Capability.ODDS));
        verify(ingestionBus).publish(any(IngestEvent.class));
        verify(scraper).fetch();
    }

    @Test
    void legacyHelpersDelegateToScraper() {
        HardRockOddsScraper scraper = mock(HardRockOddsScraper.class);
        HardRockFeedClient client = new HardRockFeedClient(scraper, mock(IngestionBus.class));
        List<String> eventIds = List.of("evt-1");

        when(scraper.fetchScoreboard()).thenReturn(List.of());
        when(scraper.fetchScoreboardByEventIds(eventIds)).thenReturn(List.of());

        client.fetchScoreboardLegacy();
        client.fetchScoreboardByEventIdsLegacy(eventIds);

        verify(scraper).fetchScoreboard();
        verify(scraper).fetchScoreboardByEventIds(eventIds);
    }
}
