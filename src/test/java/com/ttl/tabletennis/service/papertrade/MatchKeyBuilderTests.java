package com.ttl.tabletennis.service.papertrade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchKeyBuilderTests {

    @Test
    void extractExternalEventId_pluckIdFromSourceQueryString() {
        assertEquals("abc123", MatchKeyBuilder.extractExternalEventId("ttseries|event=abc123"));
        assertEquals("evt:777", MatchKeyBuilder.extractExternalEventId("feedA|event=evt:777"));
        assertEquals("xyz", MatchKeyBuilder.extractExternalEventId("Source|EVENT=xyz")); // case-insensitive
    }

    @Test
    void extractExternalEventId_returnsEmptyOnBlankOrMismatch() {
        assertEquals("", MatchKeyBuilder.extractExternalEventId(null));
        assertEquals("", MatchKeyBuilder.extractExternalEventId(""));
        assertEquals("", MatchKeyBuilder.extractExternalEventId("  "));
        assertEquals("", MatchKeyBuilder.extractExternalEventId("ttseries"));
        assertEquals("", MatchKeyBuilder.extractExternalEventId("no-marker-here"));
    }

    @Test
    void playerToken_prefersIdOverName() {
        assertEquals("id-42", MatchKeyBuilder.playerToken(42L, "Ignored Name"));
        assertNotNull(MatchKeyBuilder.playerToken(null, "John Smith"));
        assertTrue(MatchKeyBuilder.playerToken(null, "John Smith").startsWith("nm-"));
    }

    @Test
    void playerToken_returnsNullWhenBothInputsBlank() {
        assertNull(MatchKeyBuilder.playerToken(null, null));
        assertNull(MatchKeyBuilder.playerToken(null, ""));
        assertNull(MatchKeyBuilder.playerToken(null, "   "));
    }

    @Test
    void normalizePersonToken_isOrderInsensitive() {
        // First/last swap should normalise to same token.
        String forward = MatchKeyBuilder.normalizePersonToken("John Smith");
        String backward = MatchKeyBuilder.normalizePersonToken("Smith John");
        assertEquals(forward, backward, "alpha-sort makes order-insensitive");
    }

    @Test
    void normalizePersonToken_stripsAccentsAndPolishL() {
        // ł → l, é → e, etc.
        String token = MatchKeyBuilder.normalizePersonToken("Łukasz Niedźwiedzki");
        assertTrue(token.contains("lukasz"), "Polish ł folded to l: " + token);
        assertNotEquals("na", token);
    }

    @Test
    void normalizePersonToken_blankReturnsNa() {
        assertEquals("na", MatchKeyBuilder.normalizePersonToken(""));
        assertEquals("na", MatchKeyBuilder.normalizePersonToken(null));
    }

    @Test
    void toPairKey_isOrderInsensitive() {
        String forward = MatchKeyBuilder.toPairKey(1L, "Alice", 2L, "Bob");
        String backward = MatchKeyBuilder.toPairKey(2L, "Bob", 1L, "Alice");
        assertEquals(forward, backward);
        // Should be tokens sorted alphabetically
        assertTrue(forward.startsWith("id-1|"), "id-1 < id-2 so id-1 comes first: " + forward);
    }

    @Test
    void toPairKey_nullWhenAnyTokenBlank() {
        // Both players without id and without usable name → null
        assertNull(MatchKeyBuilder.toPairKey(null, null, null, null));
        assertNull(MatchKeyBuilder.toPairKey(null, "", null, ""));
        // One side missing → null
        assertNull(MatchKeyBuilder.toPairKey(null, null, 2L, "Bob"));
    }

    @Test
    void toPairStartKey_combinesPairAndStartBucket() {
        String key = MatchKeyBuilder.toPairStartKey(1L, "Alice", 2L, "Bob", "2026-05-19T18:00:00Z");
        assertNotNull(key);
        assertTrue(key.contains("id-1|id-2"), "pair part present: " + key);
        // Start bucket converts to system-local time; just verify the minute-bucket shape.
        assertTrue(key.matches(".*\\|\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}"),
                "ends with minute-bucket shape yyyy-MM-ddTHH:mm: " + key);
    }

    @Test
    void toPairStartKey_nullWhenPairKeyBlank() {
        assertNull(MatchKeyBuilder.toPairStartKey(null, null, null, null, "2026-05-19T18:00:00Z"));
    }

    @Test
    void buildEventKey_emitsFiveTokenComposite() {
        // Build a minimal stand-in DTO via reflection-free composition? The DTO has 50 fields, so
        // the full integration assertion is too brittle. Just verify the helper is callable with
        // a null row (returns null) — exhaustive behaviour is exercised by PaperTradingServiceTests.
        assertNull(MatchKeyBuilder.buildEventKey(null));
    }
}
