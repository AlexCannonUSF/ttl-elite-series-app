package com.ttl.tabletennis.prediction.staking;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Phase 06 item 7 — Prometheus surface for the v3 staking engine.
 *
 * <p>{@link StakingPolicy} calls {@link #recordDecision(StakingDecision)}
 * on every result. Two families of counters land in the Micrometer
 * registry:
 *
 * <ul>
 *   <li>{@code ttl.staking.decisions{outcome}} — total {@code BET} /
 *       {@code NO_BET} count, including the kill-switch path.</li>
 *   <li>{@code ttl.staking.exposure_cap_breach_total{cap}} — counter that
 *       drives the {@code ExposureCapBreach} alert. One increment per
 *       cap-breach reason code per decision: {@code portfolio},
 *       {@code event}, {@code player}.</li>
 * </ul>
 *
 * <p>The decision is also tagged with the first cap reason via
 * {@code outcome="NO_BET_CAP"} when applicable so dashboards can split
 * cap-related no-bets from edge / kelly / drawdown no-bets.
 */
@Component
public class StakingMetrics {

    public static final String METRIC_DECISIONS = "ttl.staking.decisions";
    public static final String METRIC_EXPOSURE_CAP_BREACH = "ttl.staking.exposure_cap_breach_total";

    static final String CAP_PORTFOLIO = "portfolio";
    static final String CAP_EVENT = "event";
    static final String CAP_PLAYER = "player";

    private static final List<String> CAP_REASON_CODES = List.of(
            StakingPolicy.REASON_MAX_OPEN_EXPOSURE,
            StakingPolicy.REASON_EVENT_EXPOSURE,
            StakingPolicy.REASON_PLAYER_EXPOSURE
    );

    private final MeterRegistry meterRegistry;

    public StakingMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordDecision(StakingDecision decision) {
        if (decision == null || meterRegistry == null) {
            return;
        }
        Counter.builder(METRIC_DECISIONS)
                .tag("outcome", outcomeTag(decision))
                .register(meterRegistry)
                .increment();

        List<String> reasons = decision.reasonCodes();
        if (reasons == null) {
            return;
        }
        if (reasons.contains(StakingPolicy.REASON_MAX_OPEN_EXPOSURE)) {
            capCounter(CAP_PORTFOLIO).increment();
        }
        if (reasons.contains(StakingPolicy.REASON_EVENT_EXPOSURE)) {
            capCounter(CAP_EVENT).increment();
        }
        if (reasons.contains(StakingPolicy.REASON_PLAYER_EXPOSURE)) {
            capCounter(CAP_PLAYER).increment();
        }
    }

    static String outcomeTag(StakingDecision decision) {
        if (decision.outcome() == StakingDecision.Outcome.BET) {
            return "BET";
        }
        List<String> reasons = decision.reasonCodes();
        if (reasons != null && reasons.contains(StakingPolicy.REASON_KILL_SWITCH_ACTIVE)) {
            return "NO_BET_KILL_SWITCH";
        }
        if (reasons != null && reasons.stream().anyMatch(CAP_REASON_CODES::contains)) {
            return "NO_BET_CAP";
        }
        return "NO_BET";
    }

    private Counter capCounter(String cap) {
        return Counter.builder(METRIC_EXPOSURE_CAP_BREACH)
                .tag("cap", cap)
                .register(meterRegistry);
    }
}
