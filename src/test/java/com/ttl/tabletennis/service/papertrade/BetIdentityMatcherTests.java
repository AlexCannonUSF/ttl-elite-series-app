package com.ttl.tabletennis.service.papertrade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BetIdentityMatcherTests {

    @Test
    void sameParticipantLoose_basicCases() {
        // Identical names → match (via NameUtils.areNamesSimilar or normalised equality)
        assertTrue(BetIdentityMatcher.isSameParticipantLoose("John Smith", "John Smith"));
        // Different last name → false
        assertFalse(BetIdentityMatcher.isSameParticipantLoose("John Smith", "John Jones"));
        // Note: behaviour of single-letter first-name matching ("J Smith" vs "John Smith")
        // depends on NameUtils' normalisation and is exercised via PaperTradingServiceTests.
    }

    @Test
    void sameParticipantLoose_blankInputsReturnFalse() {
        assertFalse(BetIdentityMatcher.isSameParticipantLoose("", "Smith"));
        assertFalse(BetIdentityMatcher.isSameParticipantLoose("Smith", null));
        assertFalse(BetIdentityMatcher.isSameParticipantLoose(null, null));
    }

    @Test
    void sameParticipantLoose_exactNormalisedEquality() {
        // Normalised lookup catches whitespace + case variants.
        assertTrue(BetIdentityMatcher.isSameParticipantLoose("john smith", "JOHN SMITH"));
        assertTrue(BetIdentityMatcher.isSameParticipantLoose("  John   Smith  ", "John Smith"));
    }

    @Test
    void isSamePair_strictAndOrderInsensitive() {
        assertTrue(BetIdentityMatcher.isSamePair("a", "b", "a", "b"));
        assertTrue(BetIdentityMatcher.isSamePair("a", "b", "b", "a"));
        assertFalse(BetIdentityMatcher.isSamePair("a", "b", "a", "c"));
        // any blank → false
        assertFalse(BetIdentityMatcher.isSamePair("a", "", "a", "b"));
        assertFalse(BetIdentityMatcher.isSamePair(null, "b", "a", "b"));
    }

    @Test
    void compatibleStartTime_withinDriftReturnsTrue() {
        // Same minute → compatible
        assertTrue(BetIdentityMatcher.isCompatibleStartTime(
                "2026-05-19T18:00:00Z", "2026-05-19T18:00:30Z"));
        // 6 hours apart → still within 720-minute window
        assertTrue(BetIdentityMatcher.isCompatibleStartTime(
                "2026-05-19T18:00:00Z", "2026-05-20T00:00:00Z"));
    }

    @Test
    void compatibleStartTime_beyondDriftReturnsFalse() {
        // 13 hours apart (780 min) → > 720
        assertFalse(BetIdentityMatcher.isCompatibleStartTime(
                "2026-05-19T18:00:00Z", "2026-05-20T07:00:00Z"));
    }

    @Test
    void compatibleStartTime_blankInputsTreatedAsCompatible() {
        assertTrue(BetIdentityMatcher.isCompatibleStartTime("", "2026-05-19T18:00:00Z"));
        assertTrue(BetIdentityMatcher.isCompatibleStartTime("2026-05-19T18:00:00Z", null));
    }

    @Test
    void shouldReplaceStartTimeIso_prefersEarlierTime() {
        assertTrue(BetIdentityMatcher.shouldReplaceStartTimeIso(
                "2026-05-19T20:00:00Z", "2026-05-19T18:00:00Z"));
        assertFalse(BetIdentityMatcher.shouldReplaceStartTimeIso(
                "2026-05-19T18:00:00Z", "2026-05-19T20:00:00Z"));
    }

    @Test
    void shouldReplaceStartTimeIso_replacesBlankCurrentWhenCandidatePresent() {
        assertTrue(BetIdentityMatcher.shouldReplaceStartTimeIso(null, "2026-05-19T18:00:00Z"));
        assertTrue(BetIdentityMatcher.shouldReplaceStartTimeIso("", "2026-05-19T18:00:00Z"));
        // Blank candidate → never replaces
        assertFalse(BetIdentityMatcher.shouldReplaceStartTimeIso("2026-05-19T18:00:00Z", null));
        assertFalse(BetIdentityMatcher.shouldReplaceStartTimeIso("2026-05-19T18:00:00Z", ""));
    }

    @Test
    void shouldReplaceStartTimeIso_equalStringsReturnFalse() {
        assertFalse(BetIdentityMatcher.shouldReplaceStartTimeIso(
                "2026-05-19T18:00:00Z", "2026-05-19T18:00:00Z"));
    }

    // isLoosePairNameMatch is exercised through PaperTradingServiceTests' integration
    // coverage — building a minimal LiveOddsRecommendationDto here would mean 50 nulls,
    // which is fragile against future DTO field additions. The matcher's per-name
    // logic is unit-tested above via isSameParticipantLoose.
}
