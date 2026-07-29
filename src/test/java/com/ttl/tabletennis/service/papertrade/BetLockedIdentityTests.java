package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeBet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BetLockedIdentityTests {

    @Test
    void effectiveExternalEventId_prefersLockedValue() {
        PaperTradeBet bet = new PaperTradeBet();
        bet.setLockedExternalEventId("locked-evt-9");
        bet.setExternalEventId("current-evt-1");

        assertEquals("locked-evt-9", BetLockedIdentity.effectiveExternalEventId(bet),
                "lock wins over current");
    }

    @Test
    void effectiveExternalEventId_fallsBackToCurrent() {
        PaperTradeBet bet = new PaperTradeBet();
        bet.setLockedExternalEventId(null);
        bet.setExternalEventId("current-evt-1");

        assertEquals("current-evt-1", BetLockedIdentity.effectiveExternalEventId(bet));
    }

    @Test
    void effectiveExternalEventId_blankWhenNothingSet() {
        assertEquals("", BetLockedIdentity.effectiveExternalEventId(new PaperTradeBet()));
        assertEquals("", BetLockedIdentity.effectiveExternalEventId(null));
    }

    @Test
    void effectiveExternalEventId_trimsWhitespace() {
        PaperTradeBet bet = new PaperTradeBet();
        bet.setLockedExternalEventId("  locked-evt-9  ");

        assertEquals("locked-evt-9", BetLockedIdentity.effectiveExternalEventId(bet));
    }

    @Test
    void effectiveSourceFeedEventId_prefersLockedValue() {
        PaperTradeBet bet = new PaperTradeBet();
        bet.setLockedSourceFeedEventId("locked-feed-7");
        bet.setLastSourceFeedEventId("last-feed-3");

        assertEquals("locked-feed-7", BetLockedIdentity.effectiveSourceFeedEventId(bet));
    }

    @Test
    void effectiveSourceFeedEventId_fallsBackToLastObserved() {
        PaperTradeBet bet = new PaperTradeBet();
        bet.setLockedSourceFeedEventId(null);
        bet.setLastSourceFeedEventId("last-feed-3");

        assertEquals("last-feed-3", BetLockedIdentity.effectiveSourceFeedEventId(bet));
    }

    @Test
    void effectiveSourceFeedEventId_blankWhenNothingSet() {
        assertEquals("", BetLockedIdentity.effectiveSourceFeedEventId(new PaperTradeBet()));
        assertEquals("", BetLockedIdentity.effectiveSourceFeedEventId(null));
    }

    @Test
    void effectiveLockedStartTimeIso_prefersLockedValue() {
        PaperTradeBet bet = new PaperTradeBet();
        bet.setLockedStartTimeIso("2026-05-19T18:00:00Z");
        bet.setStartTimeIso("2026-05-19T19:00:00Z");

        assertEquals("2026-05-19T18:00:00Z", BetLockedIdentity.effectiveLockedStartTimeIso(bet));
    }

    @Test
    void effectiveLockedStartTimeIso_fallsBackToCurrent() {
        PaperTradeBet bet = new PaperTradeBet();
        bet.setLockedStartTimeIso(null);
        bet.setStartTimeIso("2026-05-19T19:00:00Z");

        assertEquals("2026-05-19T19:00:00Z", BetLockedIdentity.effectiveLockedStartTimeIso(bet));
    }

    @Test
    void effectiveLockedStartTimeIso_blankWhenNothingSet() {
        assertEquals("", BetLockedIdentity.effectiveLockedStartTimeIso(new PaperTradeBet()));
        assertEquals("", BetLockedIdentity.effectiveLockedStartTimeIso(null));
    }
}
