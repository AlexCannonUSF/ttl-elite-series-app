package com.ttl.tabletennis.scrape;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TtSeriesFeedClientTests {

    @Test
    void pullOnceWrapsOfficialLedgerMatchesWithoutChangingPayloads() {
        TtSeriesScraper scraper = mock(TtSeriesScraper.class);
        IngestionBus ingestionBus = mock(IngestionBus.class);
        TtSeriesFeedClient client = new TtSeriesFeedClient(scraper, ingestionBus);

        TtSeriesScraper.OfficialLedgerMatch match = new TtSeriesScraper.OfficialLedgerMatch(
                "official-h2h",
                "https://www.tt-series.com/h2h/alpha-beta",
                "Alpha One",
                "Beta Two",
                "3:1",
                LocalDate.of(2026, 4, 16),
                "Alpha One"
        );
        when(scraper.lookupOfficialMatchesForPair("Alpha One", "Beta Two", 7)).thenReturn(List.of(match));

        FeedClient.PullContext ctx = new FeedClient.PullContext(
                Instant.parse("2026-04-16T12:00:00Z"),
                "corr-tts-1",
                Map.of(
                        "player1Name", "Alpha One",
                        "player2Name", "Beta Two",
                        "limit", "7"
                )
        );

        List<IngestEvent<TtSeriesScraper.OfficialLedgerMatch>> events = client.pullOnce(ctx);

        assertEquals(1, events.size());
        assertEquals(TtSeriesFeedClient.SOURCE, events.get(0).source());
        assertEquals("result.confirmed", events.get(0).topic());
        assertEquals("corr-tts-1", events.get(0).correlationId());
        assertEquals(1.0, events.get(0).confidence(), 0.0001);
        assertSame(match, events.get(0).payload());
        assertEquals(1.0, client.currentHealth().rollingSuccessRate5m(), 0.0001);
        assertTrue(client.capabilities().contains(FeedClient.Capability.RESULTS));
        verify(ingestionBus).publish(any(IngestEvent.class));
        verify(scraper).lookupOfficialMatchesForPair("Alpha One", "Beta Two", 7);
    }

    @Test
    void legacyHelpersDelegateToScraper() throws Exception {
        TtSeriesScraper scraper = mock(TtSeriesScraper.class);
        TtSeriesFeedClient client = new TtSeriesFeedClient(scraper, mock(IngestionBus.class));

        when(scraper.refreshRecentOfficialResults(2)).thenReturn(11);
        when(scraper.lookupOfficialMatchesForPair("Left", "Right", 5)).thenReturn(List.of());

        assertEquals(11, client.refreshRecentOfficialResultsLegacy(2));
        assertEquals(0, client.lookupOfficialMatchesForPairLegacy("Left", "Right", 5).size());

        verify(scraper).refreshRecentOfficialResults(2);
        verify(scraper).lookupOfficialMatchesForPair("Left", "Right", 5);
    }
}
