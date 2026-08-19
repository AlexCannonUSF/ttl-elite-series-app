package com.ttl.tabletennis.scrape;

import com.ttl.tabletennis.domain.OddsQuote;
import com.ttl.tabletennis.domain.OddsSnapshot;
import com.ttl.tabletennis.model.MatchOdds;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OddsSnapshotFactoryTests {

    private final OddsSnapshotFactory factory = new OddsSnapshotFactory();

    @Test
    void fromMatchOddsEventBuildsExpectedSnapshots() {
        MatchOdds odds = new MatchOdds(
                "Adam Staniczek",
                "Dariusz Maszczynski",
                1.74,
                2.15,
                "Adam Staniczek vs Dariusz Maszczynski",
                "TTL Elite",
                true,
                "2026-04-19T12:50:00Z",
                "HARD_ROCK_GQL"
        );
        odds.setExternalEventId("match:70578852");
        odds.setSourceFeedEventId("sr:match:70578852");

        IngestEvent<MatchOdds> event = new IngestEvent<>(
                HardRockFeedClient.SOURCE,
                "odds.updated",
                Instant.parse("2026-04-19T16:03:00Z"),
                0.95,
                "corr-1",
                "raw://1",
                odds
        );

        List<OddsSnapshot> snapshots = factory.fromMatchOddsEvent(event, odds);

        assertEquals(2, snapshots.size());
        assertEquals(HardRockFeedClient.SOURCE.id(), snapshots.get(0).getSourceId());
        assertEquals("P1", snapshots.get(0).getSide());
        assertEquals("OPEN", snapshots.get(0).getMarketState());
        assertEquals("match:70578852", snapshots.get(0).getBookerEventId());
        assertEquals(LocalDateTime.ofInstant(event.observedAt(), ZoneOffset.UTC), snapshots.get(0).getObservedAt());
        assertTrue(snapshots.get(0).getTrackedEventId().length() == 64);
        double twoWayImplied = (1.0 / 1.74) + (1.0 / 2.15);
        assertEquals((1.0 / 1.74) / twoWayImplied, snapshots.get(0).getNoVigProbability(), 0.0000001);
        assertEquals((1.0 / 2.15) / twoWayImplied, snapshots.get(1).getNoVigProbability(), 0.0000001);
        assertEquals(twoWayImplied - 1.0, snapshots.get(0).getMarketOverround(), 0.0000001);
        assertEquals(snapshots.get(0).getMarketOverround(), snapshots.get(1).getMarketOverround());
    }

    @Test
    void fromOddsQuoteBackfillsHardRockSnapshotsUsingOpenState() {
        OddsQuote quote = new OddsQuote();
        quote.setSource("HARD_ROCK");
        quote.setPlayer1Display("Adam Staniczek");
        quote.setPlayer2Display("Dariusz Maszczynski");
        quote.setPlayer1Normalized("adam staniczek");
        quote.setPlayer2Normalized("dariusz maszczynski");
        quote.setStartTimeIso("2026-04-19T12:50:00Z");
        quote.setQuoteTimestampMs(Instant.parse("2026-04-19T16:03:00Z").toEpochMilli());
        quote.setDecimalOddsPlayer1(1.74);
        quote.setDecimalOddsPlayer2(2.15);
        quote.setCorrelationId("corr-q-1");

        List<OddsSnapshot> snapshots = factory.fromOddsQuote(quote);

        assertEquals(2, snapshots.size());
        assertEquals("OPEN", snapshots.get(0).getMarketState());
        assertEquals(HardRockFeedClient.SOURCE.id(), snapshots.get(0).getSourceId());
        assertEquals("corr-q-1", snapshots.get(0).getCorrelationId());
        assertEquals(1.0 / 1.74, snapshots.get(0).getImpliedProb(), 0.0000001);
        assertEquals(1.0,
                snapshots.get(0).getNoVigProbability() + snapshots.get(1).getNoVigProbability(),
                0.0000001);
    }

    @Test
    void supportsOddsQuoteBackfillRejectsNonHardRockSources() {
        OddsQuote quote = new OddsQuote();
        quote.setSource("OTHER_BOOK");
        quote.setPlayer1Display("A");
        quote.setPlayer2Display("B");

        assertFalse(factory.supportsOddsQuoteBackfill(quote));
    }
}
