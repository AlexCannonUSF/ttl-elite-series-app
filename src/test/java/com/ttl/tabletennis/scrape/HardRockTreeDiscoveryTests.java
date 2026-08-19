package com.ttl.tabletennis.scrape;

import com.ttl.tabletennis.model.MatchOdds;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HardRockTreeDiscoveryTests {

    @Test
    void pullOncePublishesPublicTreeRowsAsIdentityUpdates() {
        HardRockOddsScraper scraper = mock(HardRockOddsScraper.class);
        IngestionBus ingestionBus = mock(IngestionBus.class);
        HardRockTreeDiscovery discovery = new HardRockTreeDiscovery(scraper, ingestionBus, true, false);

        MatchOdds row = new MatchOdds(
                "Adam Staniczek",
                "Dariusz Maszczynski",
                2.0,
                2.0,
                "Adam Staniczek vs Dariusz Maszczynski",
                "TT Cup",
                true,
                "2026-04-19T18:45:00Z",
                "HARD_ROCK_PUBLIC_TREE:web",
                "1-1",
                "LIVE_MID"
        );
        row.setExternalEventId("tree-777");
        row.setSourceConfidence(0.80);
        when(scraper.discoverPublicTreeEvents()).thenReturn(List.of(row));

        List<IngestEvent<MatchOdds>> events = discovery.pullOnce(
                new FeedClient.PullContext(Instant.parse("2026-04-19T18:45:00Z"), "corr-tree", null)
        );

        assertEquals(1, events.size());
        IngestEvent<MatchOdds> event = events.get(0);
        assertEquals(SourceId.HR_TREE, event.source());
        assertEquals(HardRockTreeDiscovery.TOPIC, event.topic());
        assertEquals("corr-tree", event.correlationId());
        assertEquals(0.80, event.confidence(), 0.0001);
        assertSame(row, event.payload());
        assertEquals(1.0, discovery.currentHealth().rollingSuccessRate5m(), 0.0001);
        assertTrue(discovery.capabilities().containsAll(Set.of(
                FeedClient.Capability.MARKET_STATE,
                FeedClient.Capability.BOOKER_EVENT_ID,
                FeedClient.Capability.SCORES
        )));
        verify(scraper).discoverPublicTreeEvents();
        verify(ingestionBus).publishAll(anyList());
    }

    @Test
    void scheduledDiscoverRunsOnlyWhenExplicitlyEnabled() {
        HardRockOddsScraper scraper = mock(HardRockOddsScraper.class);
        HardRockTreeDiscovery disabledSchedule = new HardRockTreeDiscovery(scraper, mock(IngestionBus.class), true, false);
        HardRockTreeDiscovery enabledSchedule = new HardRockTreeDiscovery(scraper, mock(IngestionBus.class), true, true);
        when(scraper.discoverPublicTreeEvents()).thenReturn(List.of());

        disabledSchedule.scheduledDiscover();
        verify(scraper, never()).discoverPublicTreeEvents();

        enabledSchedule.scheduledDiscover();
        verify(scraper).discoverPublicTreeEvents();
    }

    @Test
    void disabledDiscoveryReturnsNoEventsAndKeepsIdleHealth() {
        HardRockOddsScraper scraper = mock(HardRockOddsScraper.class);
        HardRockTreeDiscovery discovery = new HardRockTreeDiscovery(scraper, mock(IngestionBus.class), false, true);

        assertTrue(discovery.pullOnce(FeedClient.PullContext.now("corr-disabled")).isEmpty());
        assertEquals(SourceId.HR_TREE, discovery.currentHealth().source());
        assertEquals(1.0, discovery.currentHealth().rollingSuccessRate5m(), 0.0001);
        verify(scraper, never()).discoverPublicTreeEvents();
    }
}
