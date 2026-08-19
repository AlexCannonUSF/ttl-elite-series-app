package com.ttl.tabletennis.scrape;

import com.ttl.tabletennis.model.MatchOdds;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HardRockTargetedPollerTests {

    @Test
    void pullOnceQueriesExplicitEventIdsAndPublishesScoreObservations() {
        HardRockOddsScraper scraper = mock(HardRockOddsScraper.class);
        IngestionBus ingestionBus = mock(IngestionBus.class);
        HardRockTargetedPoller poller = new HardRockTargetedPoller(scraper, ingestionBus, true);

        MatchOdds row = new MatchOdds(
                "Henryk Tkaczyk",
                "Pawel Chojnacki",
                2.0,
                2.0,
                "Henryk Tkaczyk vs Pawel Chojnacki",
                "TT Elite Series",
                true,
                "2026-04-19T18:45:00Z",
                "HARD_ROCK_GQL_SCORE:web",
                "2-2",
                "LIVE_LATE"
        );
        row.setExternalEventId("tracked-222");
        row.setSourceConfidence(0.97);
        row.setMatchCompleted(false);
        when(scraper.fetchScoreboardByEventIds(List.of("tracked-222", "tracked-333")))
                .thenReturn(List.of(row));

        List<IngestEvent<MatchOdds>> events = poller.pullOnce(new FeedClient.PullContext(
                Instant.parse("2026-04-19T18:45:00Z"),
                "corr-hr-tgt",
                Map.of(
                        "externalEventId", "tracked-222",
                        "eventIds", "tracked-333 tracked-222"
                )
        ));

        assertEquals(1, events.size());
        IngestEvent<MatchOdds> event = events.get(0);
        assertEquals(SourceId.HR_TGT, event.source());
        assertEquals("score.observed", event.topic());
        assertEquals("corr-hr-tgt", event.correlationId());
        assertEquals(0.97, event.confidence(), 0.0001);
        assertSame(row, event.payload());
        assertEquals(1.0, poller.currentHealth().rollingSuccessRate5m(), 0.0001);
        assertTrue(poller.capabilities().containsAll(Set.of(
                FeedClient.Capability.SCORES,
                FeedClient.Capability.MARKET_STATE,
                FeedClient.Capability.COMPLETION_SIGNAL,
                FeedClient.Capability.BOOKER_EVENT_ID
        )));
        verify(scraper).fetchScoreboardByEventIds(List.of("tracked-222", "tracked-333"));
        verify(ingestionBus).publishAll(anyList());
    }

    @Test
    void pullOnceRequiresEventIdentityBeforeTouchingScraper() {
        HardRockOddsScraper scraper = mock(HardRockOddsScraper.class);
        HardRockTargetedPoller poller = new HardRockTargetedPoller(scraper, mock(IngestionBus.class), true);

        List<IngestEvent<MatchOdds>> events = poller.pullOnce(new FeedClient.PullContext(
                Instant.parse("2026-04-19T18:45:00Z"),
                "corr-empty",
                Map.of("player1Name", "Left", "player2Name", "Right")
        ));

        assertTrue(events.isEmpty());
        verify(scraper, never()).fetchScoreboardByEventIds(anyList());
    }

    @Test
    void disabledPollerReturnsNoEventsAndKeepsIdleHealth() {
        HardRockOddsScraper scraper = mock(HardRockOddsScraper.class);
        HardRockTargetedPoller poller = new HardRockTargetedPoller(scraper, mock(IngestionBus.class), false);

        List<IngestEvent<MatchOdds>> events = poller.pullOnce(new FeedClient.PullContext(
                Instant.parse("2026-04-19T18:45:00Z"),
                "corr-disabled",
                Map.of("externalEventId", "tracked-222")
        ));

        assertTrue(events.isEmpty());
        assertEquals(SourceId.HR_TGT, poller.currentHealth().source());
        assertEquals(1.0, poller.currentHealth().rollingSuccessRate5m(), 0.0001);
        verify(scraper, never()).fetchScoreboardByEventIds(anyList());
    }
}
