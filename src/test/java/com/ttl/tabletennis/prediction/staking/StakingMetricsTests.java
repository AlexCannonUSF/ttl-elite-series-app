package com.ttl.tabletennis.prediction.staking;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class StakingMetricsTests {

    @Test
    void recordsBetOutcomeWithBetTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StakingMetrics metrics = new StakingMetrics(registry);
        StakingPolicy policy = new StakingPolicy(
                () -> StakingPolicyConfig.defaults(), () -> Boolean.FALSE, metrics);

        StakingDecision decision = policy.decide(cleanBet());

        assertEquals(StakingDecision.Outcome.BET, decision.outcome());
        assertEquals(1.0, registry.counter(StakingMetrics.METRIC_DECISIONS, "outcome", "BET").count(), 1e-9);
        // No cap breach counters when the bet sailed through.
        assertNull(registry.find(StakingMetrics.METRIC_EXPOSURE_CAP_BREACH).counter());
    }

    @Test
    void recordsCapBreachCountersWhenPortfolioCapHits() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StakingMetrics metrics = new StakingMetrics(registry);
        StakingPolicy policy = new StakingPolicy(
                () -> StakingPolicyConfig.defaults(), () -> Boolean.FALSE, metrics);

        // Bet should fire — but portfolio cap is already exhausted by an existing 5-unit open position.
        StakingRequest request = new StakingRequest(
                "evt-cap", 10L, 20L, 10L,
                0.70, 1.9, 0.06,
                10.0,
                LocalDate.of(2026, 5, 19),
                List.of(new OpenPosition("evt-other", 30L, 40L, 30L, 5.0, LocalDate.of(2026, 5, 19))),
                List.of()
        );

        StakingDecision decision = policy.decide(request);

        assertEquals(StakingDecision.Outcome.NO_BET, decision.outcome());
        assertEquals(1.0,
                registry.counter(StakingMetrics.METRIC_EXPOSURE_CAP_BREACH, "cap", StakingMetrics.CAP_PORTFOLIO).count(),
                1e-9);
        // No cap "event" or "player" counters fired in this scenario.
        assertEquals(0.0,
                counterOrZero(registry, StakingMetrics.METRIC_EXPOSURE_CAP_BREACH, "cap", StakingMetrics.CAP_EVENT),
                1e-9);
        assertEquals(1.0,
                registry.counter(StakingMetrics.METRIC_DECISIONS, "outcome", "NO_BET_CAP").count(),
                1e-9);
    }

    @Test
    void killSwitchPathLandsOnDedicatedTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StakingMetrics metrics = new StakingMetrics(registry);
        StakingPolicy policy = new StakingPolicy(
                () -> StakingPolicyConfig.defaults(), () -> Boolean.TRUE, metrics);

        StakingDecision decision = policy.decide(cleanBet());

        assertEquals(StakingDecision.Outcome.NO_BET, decision.outcome());
        assertEquals(1.0, registry.counter(StakingMetrics.METRIC_DECISIONS, "outcome", "NO_BET_KILL_SWITCH").count(), 1e-9);
    }

    @Test
    void metricsAreNeverThrownThroughTheDecisionPath() {
        StakingMetrics throwing = new StakingMetrics(null) {
            @Override
            public void recordDecision(StakingDecision decision) {
                throw new RuntimeException("metric backend down");
            }
        };
        StakingPolicy policy = new StakingPolicy(
                () -> StakingPolicyConfig.defaults(), () -> Boolean.FALSE, throwing);

        StakingDecision decision = policy.decide(cleanBet());
        assertNotNull(decision);
    }

    private static StakingRequest cleanBet() {
        return new StakingRequest(
                "evt-1", 10L, 20L, 10L,
                0.70, 1.9, 0.06,
                10.0,
                LocalDate.of(2026, 5, 19),
                List.of(),
                List.of()
        );
    }

    private static double counterOrZero(SimpleMeterRegistry registry, String name, String... tags) {
        return registry.find(name).tags(tags).counter() == null
                ? 0.0
                : registry.find(name).tags(tags).counter().count();
    }
}
