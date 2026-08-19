package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeBet;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExposureProfileTests {

    private static final double EPS = 1e-9;

    @Test
    void fromOpenBets_returnsEmptyOnNullOrEmpty() {
        ExposureProfile a = ExposureProfile.fromOpenBets(null);
        ExposureProfile b = ExposureProfile.fromOpenBets(List.of());

        assertEquals(0, a.openBets());
        assertEquals(0.0, a.openStake(), EPS);
        assertEquals(0, b.openBets());
        assertEquals(0.0, b.openStake(), EPS);
    }

    @Test
    void fromOpenBets_aggregatesOpenStakeAndCounts() {
        ExposureProfile profile = ExposureProfile.fromOpenBets(List.of(
                openBet(1L, "Smash", 10.0),
                openBet(1L, "smash", 5.0),   // same player + same trigger after normalisation
                openBet(2L, "Topspin", 7.5),
                voidBet(3L, "Skip", 99.0)    // should be filtered (not OPEN)
        ));

        assertEquals(3, profile.openBets());
        assertEquals(22.5, profile.openStake(), 1e-6);
        assertEquals(15.0, profile.playerStake(1L), 1e-6);
        assertEquals(7.5, profile.playerStake(2L), 1e-6);
        // normaliser lower-cases triggers, so both 'Smash'/'smash' end up under "smash"
        assertEquals(15.0, profile.triggerStake("smash"), 1e-6);
        assertEquals(7.5, profile.triggerStake("topspin"), 1e-6);
    }

    @Test
    void addPlacement_returnsImmutableUpdate() {
        ExposureProfile baseline = ExposureProfile.fromOpenBets(List.of(openBet(1L, "Smash", 10.0)));

        ExposureProfile next = baseline.addPlacement(2L, "Topspin", 6.0);

        // Baseline unchanged.
        assertEquals(1, baseline.openBets());
        assertEquals(10.0, baseline.openStake(), 1e-6);
        // Next reflects the addition.
        assertEquals(2, next.openBets());
        assertEquals(16.0, next.openStake(), 1e-6);
        assertEquals(6.0, next.playerStake(2L), 1e-6);
        assertEquals(6.0, next.triggerStake("topspin"), 1e-6);
        // Old player still tracked.
        assertEquals(10.0, next.playerStake(1L), 1e-6);
    }

    @Test
    void stakeLookups_returnZeroForUnknownKeys() {
        ExposureProfile profile = ExposureProfile.fromOpenBets(List.of(openBet(1L, "Smash", 10.0)));

        assertEquals(0.0, profile.playerStake(null), EPS);
        assertEquals(0.0, profile.playerStake(99L), EPS);
        assertEquals(0.0, profile.triggerStake(null), EPS);
        assertEquals(0.0, profile.triggerStake("   "), EPS);
        assertEquals(0.0, profile.triggerStake("Backspin"), EPS);
    }

    private static PaperTradeBet openBet(Long playerId, String trigger, double stake) {
        PaperTradeBet bet = new PaperTradeBet();
        bet.setStatus(PaperTradeBet.STATUS_OPEN);
        bet.setSidePlayerId(playerId);
        bet.setTopTrigger(trigger);
        bet.setStake(stake);
        return bet;
    }

    private static PaperTradeBet voidBet(Long playerId, String trigger, double stake) {
        PaperTradeBet bet = openBet(playerId, trigger, stake);
        bet.setStatus(PaperTradeBet.STATUS_VOIDED);
        return bet;
    }
}
