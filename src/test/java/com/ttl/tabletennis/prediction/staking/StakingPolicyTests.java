package com.ttl.tabletennis.prediction.staking;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StakingPolicyTests {

    private final StakingPolicy policy = new StakingPolicy();

    @Test
    void usesFractionalKellyAndPerBetCap() {
        StakingDecision decision = policy.decide(baseRequest(0.58, 2.0, 0.08, List.of(), List.of()));

        assertEquals(StakingDecision.Outcome.BET, decision.outcome());
        assertEquals(0.16, decision.rawKellyFraction(), 1.0e-9);
        assertEquals(1.5, decision.stakeUnits(), 1.0e-9);
        assertTrue(decision.reasonCodes().contains(StakingPolicy.REASON_KELLY_CAP));
    }

    @Test
    void portfolioCapReducesStakeWithoutBreaching() {
        List<OpenPosition> open = List.of(position("other", 4L, 5L, 4L, 4.2));

        StakingDecision decision = policy.decide(baseRequest(0.58, 2.0, 0.08, open, List.of()));

        assertEquals(StakingDecision.Outcome.BET, decision.outcome());
        assertEquals(0.8, decision.stakeUnits(), 1.0e-9);
        assertEquals(4.2, decision.portfolioExposureBeforeUnits(), 1.0e-9);
        assertTrue(decision.reasonCodes().contains(StakingPolicy.REASON_MAX_OPEN_EXPOSURE));
    }

    @Test
    void exhaustedPortfolioCapReturnsNoBet() {
        List<OpenPosition> open = List.of(position("other", 4L, 5L, 4L, 5.0));

        StakingDecision decision = policy.decide(baseRequest(0.58, 2.0, 0.08, open, List.of()));

        assertEquals(StakingDecision.Outcome.NO_BET, decision.outcome());
        assertEquals(0.0, decision.stakeUnits(), 1.0e-9);
        assertTrue(decision.reasonCodes().contains(StakingPolicy.REASON_MAX_OPEN_EXPOSURE));
    }

    @Test
    void eventCapReducesSameEventExposure() {
        List<OpenPosition> open = List.of(position("event-a", null, null, null, 1.6));

        StakingDecision decision = policy.decide(baseRequest(0.58, 2.0, 0.08, open, List.of()));

        assertEquals(StakingDecision.Outcome.BET, decision.outcome());
        assertEquals(0.4, decision.stakeUnits(), 1.0e-9);
        assertEquals(1.6, decision.eventExposureBeforeUnits(), 1.0e-9);
        assertTrue(decision.reasonCodes().contains(StakingPolicy.REASON_EVENT_EXPOSURE));
    }

    @Test
    void perPlayerDailyCapReducesCorrelatedPlayerExposure() {
        List<OpenPosition> open = List.of(position("event-b", 1L, 9L, 1L, 1.2));

        StakingDecision decision = policy.decide(baseRequest(0.58, 2.0, 0.08, open, List.of()));

        assertEquals(StakingDecision.Outcome.BET, decision.outcome());
        assertEquals(0.3, decision.stakeUnits(), 1.0e-9);
        assertEquals(1.2, decision.playerExposureBeforeUnits(), 1.0e-9);
        assertTrue(decision.reasonCodes().contains(StakingPolicy.REASON_PLAYER_EXPOSURE));
    }

    @Test
    void sameEventOppositeSideIsBlocked() {
        List<OpenPosition> open = List.of(position("event-a", 1L, 2L, 2L, 0.5));

        StakingDecision decision = policy.decide(baseRequest(0.58, 2.0, 0.08, open, List.of()));

        assertEquals(StakingDecision.Outcome.NO_BET, decision.outcome());
        assertTrue(decision.reasonCodes().contains(StakingPolicy.REASON_CORRELATED_OPPOSITE_SIDE));
    }

    @Test
    void drawdownStopHalvesStakeAfterRollingLossThreshold() {
        List<SettledStake> settled = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            settled.add(new SettledStake(1.0, -0.10));
        }

        StakingDecision decision = policy.decide(baseRequest(0.58, 2.0, 0.08, List.of(), settled));

        assertEquals(StakingDecision.Outcome.BET, decision.outcome());
        assertEquals(-0.10, decision.drawdownRoi(), 1.0e-9);
        assertEquals(0.50, decision.drawdownFactor(), 1.0e-9);
        assertEquals(0.75, decision.stakeUnits(), 1.0e-9);
        assertTrue(decision.reasonCodes().contains(StakingPolicy.REASON_DRAWDOWN_STOP));
    }

    @Test
    void lowEdgeIsFirstClassNoBet() {
        StakingDecision decision = policy.decide(baseRequest(0.58, 2.0, 0.01, List.of(), List.of()));

        assertEquals(StakingDecision.Outcome.NO_BET, decision.outcome());
        assertEquals(0.025, decision.requiredEdge(), 1.0e-9);
        assertTrue(decision.reasonCodes().contains(StakingPolicy.REASON_EDGE_BELOW_THRESHOLD));
    }

    @Test
    void highOddsScaleMinimumEdgeThreshold() {
        assertEquals(0.05, policy.requiredEdge(3.0), 1.0e-9);
        assertEquals(0.025, policy.requiredEdge(1.80), 1.0e-9);
    }

    @Test
    void invalidInputsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> baseRequest(1.2, 2.0, 0.08, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> baseRequest(0.58, 1.0, 0.08, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new StakingPolicyConfig(1.5, 1.0, 2.0, 1.5, 5.0, 0.1, 0.025, 50, -0.08, 0.5));
    }

    private StakingRequest baseRequest(double p,
                                       double decimalOdds,
                                       double edge,
                                       List<OpenPosition> open,
                                       List<SettledStake> settled) {
        return new StakingRequest(
                "event-a",
                1L,
                2L,
                1L,
                p,
                decimalOdds,
                edge,
                100.0,
                LocalDate.of(2026, 5, 18),
                open,
                settled
        );
    }

    private OpenPosition position(String eventKey, Long player1Id, Long player2Id, Long sidePlayerId, double stake) {
        assertFalse(eventKey.isBlank());
        return new OpenPosition(
                eventKey,
                player1Id,
                player2Id,
                sidePlayerId,
                stake,
                LocalDate.of(2026, 5, 18)
        );
    }
}
