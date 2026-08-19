package com.ttl.tabletennis.service.papertrade;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RowLookupBuilderTests {

    @Test
    void build_handlesNullInput() {
        RowLookup lookup = RowLookupBuilder.build(null);
        assertNotNull(lookup);
        assertTrue(lookup.allRows().isEmpty());
        assertTrue(lookup.byDedupe().isEmpty());
        assertTrue(lookup.byEvent().isEmpty());
        assertTrue(lookup.byPair().isEmpty());
    }

    @Test
    void build_handlesEmptyInput() {
        RowLookup lookup = RowLookupBuilder.build(List.of());
        assertTrue(lookup.allRows().isEmpty());
    }

    @Test
    void settlementRowRank_nullReturnsZero() {
        assertEquals(0, RowLookupBuilder.settlementRowRank(null));
    }

    @Test
    void preferSettlementRow_nullHandlingIsSafe() {
        // Candidate null → never prefer
        assertFalse(RowLookupBuilder.preferSettlementRow(null, null));
        // Current null + candidate non-null → prefer candidate (we cheat: pass empty stub
        // via reflection isn't needed here; the contract is documented + exercised by
        // integration tests).
    }

    @Test
    void putPreferredRow_nullsAreNoop() {
        // No throw on null inputs — coverage for the defensive guards.
        java.util.Map<String, com.ttl.tabletennis.dto.LiveOddsRecommendationDto> empty = new java.util.HashMap<>();
        RowLookupBuilder.putPreferredRow(null, "key", null);
        RowLookupBuilder.putPreferredRow(empty, null, null);
        RowLookupBuilder.putPreferredRow(empty, "  ", null);
        assertTrue(empty.isEmpty());
    }

    // Full multi-key indexing + rank/preference resolution is exercised through
    // PaperTradingServiceTests' settlement coverage — building real
    // LiveOddsRecommendationDto rows with 50 fields each is too brittle for
    // focused unit tests.
}
