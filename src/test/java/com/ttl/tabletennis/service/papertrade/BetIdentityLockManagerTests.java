package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.TrackedMatchObservation;
import com.ttl.tabletennis.dto.LiveOddsRecommendationDto;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BetIdentityLockManagerTests {

    // --- helpers for #114 fallback tests ---

    private static PaperTradeBet lockedBet(String externalId,
                                             String sourceFeedId,
                                             String startIso,
                                             String p1,
                                             String p2) {
        PaperTradeBet bet = new PaperTradeBet();
        bet.setIdentityLocked(true);
        bet.setLockedExternalEventId(externalId);
        bet.setLockedSourceFeedEventId(sourceFeedId);
        bet.setLockedStartTimeIso(startIso);
        bet.setExternalEventId(externalId);
        bet.setStartTimeIso(startIso);
        bet.setPlayer1Name(p1);
        bet.setPlayer2Name(p2);
        return bet;
    }

    private static LiveOddsRecommendationDto liveRow(String externalId,
                                                       String sourceFeedId,
                                                       String startIso,
                                                       String p1,
                                                       String p2) {
        return new LiveOddsRecommendationDto(
                "HARD_ROCK_GQL:FLORIDA_ONLINE|event=" + (externalId == null ? "" : externalId),
                "CONSERVATIVE",
                "ENSEMBLE",
                p1 + " vs " + p2,
                "TT Elite Series",
                false,
                startIso,
                null,
                null,
                1L, p1, 2L, p2,
                1.91, 1.91, -110, -110,
                0.524, 0.524,
                null, null, null, null, null, null,
                p1, null, null, null, null,
                false, "B", null, null, null, null, null, null, null,
                "matchup", "dedupe",
                "MARKET",
                0.9,
                externalId,
                true,
                false,
                false,
                "HARD_ROCK_GQL",
                sourceFeedId,
                null);
    }

    private static TrackedMatchObservation observation(String externalId,
                                                         String sourceFeedId,
                                                         String startIso,
                                                         String p1,
                                                         String p2) {
        TrackedMatchObservation o = new TrackedMatchObservation();
        o.setExternalEventId(externalId);
        o.setSourceFeedEventId(sourceFeedId);
        o.setStartTimeIso(startIso);
        o.setPlayer1Name(p1);
        o.setPlayer2Name(p2);
        o.setObservedAt(LocalDateTime.parse("2026-05-24T16:50:00"));
        return o;
    }

    @Test
    void lockBetIdentityIfEligible_pinsLockWithStrongIdentity() {
        PaperTradeBet bet = new PaperTradeBet();
        bet.setExternalEventId("evt-9");
        LocalDateTime now = LocalDateTime.parse("2026-05-19T18:00:00");

        boolean changed = BetIdentityLockManager.lockBetIdentityIfEligible(bet, now);

        assertTrue(changed);
        assertTrue(bet.isIdentityLocked());
        assertEquals(now, bet.getIdentityLockedAt());
        assertEquals("evt-9", bet.getLockedExternalEventId());
    }

    @Test
    void lockBetIdentityIfEligible_skipsWhenNoStrongIdentity() {
        PaperTradeBet bet = new PaperTradeBet();
        // No external id, no source feed id → no strong identity
        assertFalse(BetIdentityLockManager.lockBetIdentityIfEligible(bet, LocalDateTime.now()));
        assertFalse(bet.isIdentityLocked());
    }

    @Test
    void lockBetIdentityIfEligible_idempotentWhenAlreadyLocked() {
        PaperTradeBet bet = new PaperTradeBet();
        bet.setExternalEventId("evt-9");
        bet.setIdentityLocked(true);
        bet.setIdentityLockedAt(LocalDateTime.parse("2026-05-19T18:00:00"));
        bet.setLockedExternalEventId("evt-9");

        boolean changed = BetIdentityLockManager.lockBetIdentityIfEligible(bet, LocalDateTime.now());
        assertFalse(changed, "re-locking with same identity is a no-op");
    }

    @Test
    void rowMatchesLockedIdentity_unconstrainedWhenBetNotLocked() {
        PaperTradeBet bet = new PaperTradeBet();
        bet.setIdentityLocked(false);
        // Even a bogus row should pass when bet isn't locked
        assertTrue(BetIdentityLockManager.rowMatchesLockedIdentity(bet, null));
    }

    @Test
    void rowMatchesLockedIdentity_nullArgsTreatedAsUnconstrained() {
        assertTrue(BetIdentityLockManager.rowMatchesLockedIdentity(null, null));
        PaperTradeBet bet = new PaperTradeBet();
        bet.setIdentityLocked(true);
        assertTrue(BetIdentityLockManager.rowMatchesLockedIdentity(bet, null));
    }

    @Test
    void observationMatchesLockedIdentity_unconstrainedWhenBetNotLocked() {
        PaperTradeBet bet = new PaperTradeBet();
        bet.setIdentityLocked(false);
        assertTrue(BetIdentityLockManager.observationMatchesLockedIdentity(bet, null));
    }

    @Test
    void markIdentityDriftAttempt_skipsWhenBetNotLocked() {
        PaperTradeBet bet = new PaperTradeBet();
        bet.setIdentityLocked(false);
        int before = bet.getIdentityDriftCount();
        BetIdentityLockManager.markIdentityDriftAttempt(bet, "candidate-evt", "candidate-feed", "2026-05-19T18:00:00", LocalDateTime.now(), "TEST");
        assertEquals(before, bet.getIdentityDriftCount(), "no drift counter bump when not locked");
    }

    @Test
    void markIdentityDriftAttempt_bumpsCounterWhenLocked() {
        PaperTradeBet bet = new PaperTradeBet();
        bet.setIdentityLocked(true);
        bet.setLockedExternalEventId("locked-evt");
        bet.setIdentityDriftCount(2);

        LocalDateTime when = LocalDateTime.parse("2026-05-19T20:00:00");
        BetIdentityLockManager.markIdentityDriftAttempt(bet, "different-evt", null, null, when, "CONFLICT");

        assertEquals(3, bet.getIdentityDriftCount());
        assertEquals(when, bet.getLastIdentityDriftAt());
    }

    @Test
    void markIdentityDriftAttempt_defaultsObservedAtToNowWhenNull() {
        PaperTradeBet bet = new PaperTradeBet();
        bet.setIdentityLocked(true);
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        BetIdentityLockManager.markIdentityDriftAttempt(bet, "x", null, null, null, "TEST");

        assertTrue(bet.getLastIdentityDriftAt().isAfter(before), "null observedAt falls back to LocalDateTime.now()");
    }

    // --- #114 fallback tests: cross-feed identity drift recovery ---

    @Test
    void rowMatches_acceptsExactIdMatch_evenWhenOtherIdsDiffer() {
        PaperTradeBet bet = lockedBet("5306454809325470015", "sr:match:71680246",
                "2026-05-24T20:30:00Z", "Mikolaj Lukaszewski", "Adrian Fabis");
        LiveOddsRecommendationDto row = liveRow("5306454809325470015", "DIFFERENT_FEED_ID_999",
                "2026-05-24T20:30:00Z", "Mikolaj Lukaszewski", "Adrian Fabis");

        assertTrue(BetIdentityLockManager.rowMatchesLockedIdentity(bet, row),
                "outer externalEventId matches → fast accept regardless of sourceFeedEventId mismatch");
    }

    @Test
    void rowMatches_acceptsSourceFeedIdMatch_evenWhenExternalDiffers() {
        PaperTradeBet bet = lockedBet("HR-OLD-123", "sr:match:71680246",
                "2026-05-24T20:30:00Z", "Mikolaj Lukaszewski", "Adrian Fabis");
        LiveOddsRecommendationDto row = liveRow("HR-NEW-456", "sr:match:71680246",
                "2026-05-24T20:30:00Z", "Mikolaj Lukaszewski", "Adrian Fabis");

        assertTrue(BetIdentityLockManager.rowMatchesLockedIdentity(bet, row),
                "sourceFeedEventId matches → fast accept regardless of externalEventId mismatch");
    }

    @Test
    void rowMatches_idsDisagree_butSamePlayersAndTime_rescuesObservation() {
        // The bug #113 / fix #114 scenario: HardRock outer ID and BETRADAR_UF
        // inner ID disagree for the same physical match, but players + time
        // confirm it's the same.
        PaperTradeBet bet = lockedBet("5306454809325470015", "sr:match:71680246",
                "2026-05-24T20:30:00Z", "Mikolaj Lukaszewski", "Adrian Fabis");
        LiveOddsRecommendationDto row = liveRow("DIFFERENT_HR_999999", "sr:match:DIFFERENT",
                "2026-05-24T20:30:00Z", "Mikolaj Lukaszewski", "Adrian Fabis");

        assertTrue(BetIdentityLockManager.rowMatchesLockedIdentity(bet, row),
                "ID mismatch + same players + same time → name+time fallback rescues observation (#114)");
    }

    @Test
    void rowMatches_idsDisagree_playerOrderSwapped_stillRescues() {
        PaperTradeBet bet = lockedBet("evt-A", "feed-A",
                "2026-05-24T20:30:00Z", "Mikolaj Lukaszewski", "Adrian Fabis");
        LiveOddsRecommendationDto row = liveRow("evt-B", "feed-B",
                "2026-05-24T20:30:00Z", "Adrian Fabis", "Mikolaj Lukaszewski");

        assertTrue(BetIdentityLockManager.rowMatchesLockedIdentity(bet, row),
                "isLoosePairNameMatch tolerates swapped P1/P2 order");
    }

    @Test
    void rowMatches_idsDisagree_differentPlayers_rejected() {
        PaperTradeBet bet = lockedBet("evt-A", "feed-A",
                "2026-05-24T20:30:00Z", "Mikolaj Lukaszewski", "Adrian Fabis");
        LiveOddsRecommendationDto row = liveRow("evt-B", "feed-B",
                "2026-05-24T20:30:00Z", "Lukasz Pietraszko", "Adam Linek");

        assertFalse(BetIdentityLockManager.rowMatchesLockedIdentity(bet, row),
                "ID mismatch + different players → reject (preserves drift detection)");
    }

    @Test
    void rowMatches_idsDisagree_samePlayersButDistantTime_rejected() {
        PaperTradeBet bet = lockedBet("evt-A", "feed-A",
                "2026-05-24T20:30:00Z", "Mikolaj Lukaszewski", "Adrian Fabis");
        // 13+ hours later — well past the MAX_START_TIME_DRIFT_MINUTES window (720 min)
        LiveOddsRecommendationDto row = liveRow("evt-B", "feed-B",
                "2026-05-25T10:30:00Z", "Mikolaj Lukaszewski", "Adrian Fabis");

        assertFalse(BetIdentityLockManager.rowMatchesLockedIdentity(bet, row),
                "ID mismatch + same players but distant start time → reject (different match)");
    }

    @Test
    void rowMatches_blankIdsOnOneSide_softPathUsesTimeOnly() {
        PaperTradeBet bet = lockedBet("evt-A", null,
                "2026-05-24T20:30:00Z", "Mikolaj Lukaszewski", "Adrian Fabis");
        LiveOddsRecommendationDto row = liveRow(null, null,
                "2026-05-24T20:30:00Z", "Different Player", "Other Person");

        // No ID disagreement (candidate has no IDs to compare). Start times
        // compatible. Original soft-path accepts even with different player names.
        assertTrue(BetIdentityLockManager.rowMatchesLockedIdentity(bet, row),
                "blank candidate IDs → soft path (backward-compat)");
    }

    @Test
    void observationMatches_idsDisagree_samePlayersAndTime_rescuesObservation() {
        PaperTradeBet bet = lockedBet("5306454809325470015", "sr:match:71680246",
                "2026-05-24T20:30:00Z", "Mikolaj Lukaszewski", "Adrian Fabis");
        TrackedMatchObservation o = observation("DIFFERENT_HR_999999", "sr:match:DIFFERENT",
                "2026-05-24T20:30:00Z", "Mikolaj Lukaszewski", "Adrian Fabis");

        assertTrue(BetIdentityLockManager.observationMatchesLockedIdentity(bet, o),
                "observation fallback rescue mirrors row fallback rescue (#114)");
    }

    @Test
    void observationMatches_idsDisagree_differentPlayers_rejected() {
        PaperTradeBet bet = lockedBet("evt-A", "feed-A",
                "2026-05-24T20:30:00Z", "Mikolaj Lukaszewski", "Adrian Fabis");
        TrackedMatchObservation o = observation("evt-B", "feed-B",
                "2026-05-24T20:30:00Z", "Lukasz Pietraszko", "Adam Linek");

        assertFalse(BetIdentityLockManager.observationMatchesLockedIdentity(bet, o),
                "observation drift rejection preserved for genuinely-different matches");
    }

    // --- #116 tests: Observer hook fires for drift attempts and fallback rescues ---

    @Test
    void observer_firesOnDriftAttempt() {
        java.util.concurrent.atomic.AtomicReference<String> lastReason = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger driftCount = new java.util.concurrent.atomic.AtomicInteger();
        BetIdentityLockManager.setObserver(new BetIdentityLockManager.Observer() {
            @Override
            public void onDriftAttempt(String reason) {
                lastReason.set(reason);
                driftCount.incrementAndGet();
            }
        });
        try {
            PaperTradeBet bet = new PaperTradeBet();
            bet.setIdentityLocked(true);
            BetIdentityLockManager.markIdentityDriftAttempt(bet, "candidate-evt", null, null,
                    LocalDateTime.now(), "TEST_REASON");

            assertEquals(1, driftCount.get(), "observer.onDriftAttempt should fire once per drift");
            assertEquals("TEST_REASON", lastReason.get(), "reason propagated to observer");
        } finally {
            BetIdentityLockManager.setObserver(null); // reset
        }
    }

    @Test
    void observer_firesOnFallbackRescue() {
        java.util.concurrent.atomic.AtomicInteger rescued = new java.util.concurrent.atomic.AtomicInteger();
        BetIdentityLockManager.setObserver(new BetIdentityLockManager.Observer() {
            @Override
            public void onFallbackRescued() {
                rescued.incrementAndGet();
            }
        });
        try {
            PaperTradeBet bet = lockedBet("evt-A", "feed-A",
                    "2026-05-24T20:30:00Z", "Mikolaj Lukaszewski", "Adrian Fabis");
            LiveOddsRecommendationDto row = liveRow("evt-B", "feed-B",
                    "2026-05-24T20:30:00Z", "Mikolaj Lukaszewski", "Adrian Fabis");

            assertTrue(BetIdentityLockManager.rowMatchesLockedIdentity(bet, row));
            assertEquals(1, rescued.get(), "observer.onFallbackRescued should fire when name+time saves the match");
        } finally {
            BetIdentityLockManager.setObserver(null);
        }
    }

    @Test
    void observer_doesNotFireOnExactIdMatch() {
        java.util.concurrent.atomic.AtomicInteger rescued = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger drift = new java.util.concurrent.atomic.AtomicInteger();
        BetIdentityLockManager.setObserver(new BetIdentityLockManager.Observer() {
            @Override
            public void onDriftAttempt(String reason) { drift.incrementAndGet(); }
            @Override
            public void onFallbackRescued() { rescued.incrementAndGet(); }
        });
        try {
            PaperTradeBet bet = lockedBet("evt-A", "feed-A",
                    "2026-05-24T20:30:00Z", "P1", "P2");
            LiveOddsRecommendationDto row = liveRow("evt-A", "feed-A",
                    "2026-05-24T20:30:00Z", "P1", "P2");

            assertTrue(BetIdentityLockManager.rowMatchesLockedIdentity(bet, row));
            assertEquals(0, drift.get(), "no drift on exact match");
            assertEquals(0, rescued.get(), "no fallback-rescue counter increment on fast-path match");
        } finally {
            BetIdentityLockManager.setObserver(null);
        }
    }
}
