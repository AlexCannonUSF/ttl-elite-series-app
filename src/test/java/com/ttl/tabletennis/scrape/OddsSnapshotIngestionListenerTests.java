package com.ttl.tabletennis.scrape;

import com.ttl.tabletennis.domain.OddsSnapshot;
import com.ttl.tabletennis.model.MatchOdds;
import com.ttl.tabletennis.repository.OddsSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OddsSnapshotIngestionListenerTests {

    @Test
    void onIngestEventPersistsTwoSnapshotsForHardRockOddsRows() {
        OddsSnapshotRepository repository = mock(OddsSnapshotRepository.class);
        OddsSnapshotIngestionListener listener = new OddsSnapshotIngestionListener(repository, new OddsSnapshotFactory());

        MatchOdds odds = new MatchOdds(
                "Adam Staniczek",
                "Dariusz Maszczynski",
                1.74,
                2.15,
                "Adam Staniczek vs Dariusz Maszczynski",
                "TTL Elite",
                true,
                "2026-04-19T12:50:00Z",
                "HARD_ROCK_GRAPHQL"
        );
        odds.setExternalEventId("match:70578852");
        odds.setSourceFeedEventId("sr:match:70578852");

        IngestEvent<MatchOdds> event = new IngestEvent<>(
                HardRockFeedClient.SOURCE,
                "odds.updated",
                Instant.parse("2026-04-19T16:03:00Z"),
                0.95,
                "corr-odds-1",
                "raw://hardrock/1",
                odds
        );

        listener.onIngestEvent(event);

        ArgumentCaptor<List<OddsSnapshot>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());

        List<OddsSnapshot> snapshots = captor.getValue();
        assertEquals(2, snapshots.size());
        assertEquals("P1", snapshots.get(0).getSide());
        assertEquals("P2", snapshots.get(1).getSide());
        assertEquals("OPEN", snapshots.get(0).getMarketState());
        assertEquals("match:70578852", snapshots.get(0).getBookerEventId());
        assertEquals("corr-odds-1", snapshots.get(0).getCorrelationId());
        assertEquals(LocalDateTime.ofInstant(event.observedAt(), ZoneOffset.UTC), snapshots.get(0).getObservedAt());
        assertTrue(snapshots.get(0).getTrackedEventId().length() == 64);
        assertEquals(1.0 / 1.74, snapshots.get(0).getImpliedProb(), 0.0000001);
        assertEquals(1.0 / 2.15, snapshots.get(1).getImpliedProb(), 0.0000001);
        assertTrue(snapshots.get(0).getMatchKey().contains("adam"));
        assertTrue(snapshots.get(0).getMatchKey().contains("dariusz"));
    }

    @Test
    void buildSnapshotsMapsHiddenAndResultedRowsToExpectedMarketStates() {
        OddsSnapshotFactory factory = new OddsSnapshotFactory();

        MatchOdds hidden = new MatchOdds("A", "B", 1.80, 2.00);
        hidden.setDisplayed(false);

        List<OddsSnapshot> hiddenSnapshots = factory.fromMatchOddsEvent(new IngestEvent<>(
                HardRockFeedClient.SOURCE,
                "odds.updated",
                Instant.parse("2026-04-19T16:03:00Z"),
                0.90,
                "corr-hidden",
                "",
                hidden
        ), hidden);
        assertFalse(hiddenSnapshots.isEmpty());
        assertEquals("SUSPENDED", hiddenSnapshots.get(0).getMarketState());

        MatchOdds resulted = new MatchOdds("A", "B", 1.80, 2.00);
        resulted.setResulted(true);

        List<OddsSnapshot> closedSnapshots = factory.fromMatchOddsEvent(new IngestEvent<>(
                HardRockFeedClient.SOURCE,
                "odds.updated",
                Instant.parse("2026-04-19T16:03:00Z"),
                0.90,
                "corr-closed",
                "",
                resulted
        ), resulted);
        assertFalse(closedSnapshots.isEmpty());
        assertEquals("CLOSED", closedSnapshots.get(0).getMarketState());
    }

    @Test
    void onIngestEventIgnoresNonHardRockSources() {
        OddsSnapshotRepository repository = mock(OddsSnapshotRepository.class);
        OddsSnapshotIngestionListener listener = new OddsSnapshotIngestionListener(repository, new OddsSnapshotFactory());

        listener.onIngestEvent(new IngestEvent<>(
                TtSeriesFeedClient.SOURCE,
                "result.confirmed",
                Instant.now(),
                1.0,
                "corr-tts",
                "",
                new TtSeriesScraper.OfficialLedgerMatch("official", "url", "A", "B", "3:1", null, "A")
        ));

        verify(repository, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
    }
}
