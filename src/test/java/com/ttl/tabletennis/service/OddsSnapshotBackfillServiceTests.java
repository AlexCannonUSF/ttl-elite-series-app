package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.OddsQuote;
import com.ttl.tabletennis.domain.OddsSnapshot;
import com.ttl.tabletennis.repository.OddsQuoteRepository;
import com.ttl.tabletennis.repository.OddsSnapshotRepository;
import com.ttl.tabletennis.scrape.OddsSnapshotFactory;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OddsSnapshotBackfillServiceTests {

    @Test
    void backfillHistoricalQuotesPersistsOnlyMissingSnapshots() {
        OddsQuoteRepository oddsQuoteRepository = mock(OddsQuoteRepository.class);
        OddsSnapshotRepository oddsSnapshotRepository = mock(OddsSnapshotRepository.class);
        OddsSnapshotFactory factory = new OddsSnapshotFactory();
        OddsSnapshotBackfillService service = new OddsSnapshotBackfillService(
                oddsQuoteRepository,
                oddsSnapshotRepository,
                factory
        );

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

        when(oddsQuoteRepository.findAllByOrderByScrapedAtAscIdAsc(PageRequest.of(0, 500)))
                .thenReturn(new SliceImpl<>(List.of(quote), PageRequest.of(0, 500), false));
        when(oddsSnapshotRepository.existsByTrackedEventIdAndSideAndObservedAtAndPriceDecimalAndSourceId(
                anyString(), anyString(), any(), anyDouble(), anyString()))
                .thenReturn(false, true);

        OddsSnapshotBackfillService.BackfillResult result = service.backfillHistoricalQuotes(500, 0);

        assertEquals(1, result.scannedQuotes());
        assertEquals(1, result.eligibleQuotes());
        assertEquals(1, result.persistedSnapshots());
        assertEquals(1, result.skippedSnapshots());
        assertEquals(1, result.pagesProcessed());
        verify(oddsSnapshotRepository, times(1)).saveAll(any(List.class));
    }
}
