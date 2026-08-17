package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.MarketBook;
import com.ttl.tabletennis.domain.OddsSnapshot;
import com.ttl.tabletennis.dto.MarketIntelligenceDto;
import com.ttl.tabletennis.repository.MarketBookRepository;
import com.ttl.tabletennis.repository.OddsSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketIntelligenceServiceTests {

    @Test
    void separatesExecutableBookFromReferenceAndBuildsNoVigConsensus() {
        OddsSnapshotRepository snapshots = mock(OddsSnapshotRepository.class);
        MarketBookRepository books = mock(MarketBookRepository.class);
        MarketIntelligenceService service = new MarketIntelligenceService(snapshots, books);
        LocalDateTime observed = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(2);
        when(snapshots.findMarketHistory(eq("event-1"), any(Pageable.class))).thenReturn(List.of(
                quote("HR_MKT", "P1", 2.10, 0.45, observed),
                quote("HR_MKT", "P2", 1.75, 0.55, observed),
                quote("DK_REF", "P1", 1.82, 0.55, observed),
                quote("DK_REF", "P2", 2.10, 0.45, observed)));
        when(books.findByEnabledTrueOrderByDisplayNameAsc()).thenReturn(List.of(
                book("HR_MKT", "Hard Rock Bet", "EXECUTABLE", true, 1.0),
                book("DK_REF", "DraftKings reference", "REFERENCE", false, 1.0)));

        MarketIntelligenceDto result = service.market("event-1", 100);

        assertTrue(result.executionAvailable());
        assertEquals(2, result.sourceCount());
        assertEquals(2, result.consensusSourceCount());
        assertEquals(0.5, result.consensusPlayer1Probability(), 0.0001);
        assertEquals(5.0, result.consensusDispersionPctPoints(), 0.0001);
        assertTrue(result.books().get(0).executable());
        assertFalse(result.books().get(1).executable());
        assertTrue(result.warnings().isEmpty());
    }

    private static OddsSnapshot quote(String source, String side, double decimal, double noVig, LocalDateTime observed) {
        OddsSnapshot row = new OddsSnapshot();
        row.setTrackedEventId("event-1");
        row.setSourceId(source);
        row.setSide(side);
        row.setPriceDecimal(decimal);
        row.setImpliedProb(1.0 / decimal);
        row.setNoVigProbability(noVig);
        row.setMarketOverround(0.05);
        row.setMarketState("OPEN");
        row.setObservedAt(observed);
        return row;
    }

    private static MarketBook book(String source, String name, String role, boolean authorized, double weight) {
        MarketBook row = new MarketBook();
        row.setSourceCode(source);
        row.setDisplayName(name);
        row.setMarketRole(role);
        row.setAuthorized(authorized);
        row.setEnabled(true);
        row.setConsensusWeight(weight);
        return row;
    }
}
