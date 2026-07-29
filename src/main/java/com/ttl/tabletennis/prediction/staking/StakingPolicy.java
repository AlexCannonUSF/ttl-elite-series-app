package com.ttl.tabletennis.prediction.staking;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Phase 06 staking layer: turns a selected edge into a stake or an explicit
 * no-bet decision using fractional Kelly, exposure caps, correlation caps,
 * and the rolling drawdown stop from Prediction Engine Spec §9.
 */
@Service
public class StakingPolicy {

    public static final String REASON_EDGE_BELOW_THRESHOLD = "EDGE_BELOW_THRESHOLD";
    public static final String REASON_NEGATIVE_KELLY = "NEGATIVE_KELLY";
    public static final String REASON_KELLY_CAP = "KELLY_CAP";
    public static final String REASON_DRAWDOWN_STOP = "DRAWDOWN_STOP";
    public static final String REASON_MAX_OPEN_EXPOSURE = "MAX_OPEN_EXPOSURE_CAP";
    public static final String REASON_EVENT_EXPOSURE = "EVENT_EXPOSURE_CAP";
    public static final String REASON_PLAYER_EXPOSURE = "PLAYER_DAILY_EXPOSURE_CAP";
    public static final String REASON_CORRELATED_OPPOSITE_SIDE = "CORRELATED_OPPOSITE_SIDE";
    public static final String REASON_STAKE_BELOW_MIN = "STAKE_BELOW_MIN";
    public static final String REASON_KILL_SWITCH_ACTIVE = "KILL_SWITCH_ACTIVE";

    private final Supplier<StakingPolicyConfig> configSupplier;
    private final Supplier<Boolean> killSwitchSupplier;
    private final StakingMetrics metrics;

    @Autowired
    public StakingPolicy(StakingPolicyCatalog catalog,
                         StakingKillSwitch killSwitch,
                         StakingMetrics metrics) {
        this(catalog::currentConfig, killSwitch::isActive, metrics);
    }

    public StakingPolicy(StakingPolicyCatalog catalog, StakingKillSwitch killSwitch) {
        this(catalog::currentConfig, killSwitch::isActive, null);
    }

    public StakingPolicy() {
        this(StakingPolicyConfig.defaults());
    }

    public StakingPolicy(StakingPolicyConfig config) {
        this(() -> requireConfig(config), () -> Boolean.FALSE, null);
    }

    StakingPolicy(Supplier<StakingPolicyConfig> configSupplier, Supplier<Boolean> killSwitchSupplier) {
        this(configSupplier, killSwitchSupplier, null);
    }

    StakingPolicy(Supplier<StakingPolicyConfig> configSupplier,
                  Supplier<Boolean> killSwitchSupplier,
                  StakingMetrics metrics) {
        if (configSupplier == null) {
            throw new IllegalArgumentException("configSupplier must not be null");
        }
        this.configSupplier = configSupplier;
        this.killSwitchSupplier = killSwitchSupplier == null ? () -> Boolean.FALSE : killSwitchSupplier;
        this.metrics = metrics;
    }

    private static StakingPolicyConfig requireConfig(StakingPolicyConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        return config;
    }

    public StakingPolicyConfig config() {
        return configSupplier.get();
    }

    public StakingDecision decide(StakingRequest request) {
        StakingDecision decision = computeDecision(request);
        if (metrics != null) {
            try {
                metrics.recordDecision(decision);
            } catch (RuntimeException ignored) {
                // Metrics must never break the decision path.
            }
        }
        return decision;
    }

    private StakingDecision computeDecision(StakingRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }

        StakingPolicyConfig config = configSupplier.get();
        boolean killSwitchActive = Boolean.TRUE.equals(killSwitchSupplier.get());

        List<String> reasons = new ArrayList<>();
        double requiredEdge = requiredEdge(config, request.decimalOdds());

        if (killSwitchActive) {
            reasons.add(REASON_KILL_SWITCH_ACTIVE);
            return new StakingDecision(
                    StakingDecision.Outcome.NO_BET,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    requiredEdge,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    1.0,
                    distinct(reasons)
            );
        }

        if (request.selectedEdge() < requiredEdge) {
            reasons.add(REASON_EDGE_BELOW_THRESHOLD);
        }

        double rawKelly = rawKellyFraction(request.modelProbability(), request.decimalOdds());
        if (rawKelly <= 0.0) {
            reasons.add(REASON_NEGATIVE_KELLY);
        }

        Exposure exposure = exposure(request);
        if (exposure.hasOppositeSide()) {
            reasons.add(REASON_CORRELATED_OPPOSITE_SIDE);
        }

        double uncappedStake = Math.max(0.0, rawKelly) * config.fractionalKelly() * request.bankrollUnits();
        double kellyCappedStake = Math.min(uncappedStake, config.kellyCapUnits());
        if (uncappedStake > config.kellyCapUnits()) {
            reasons.add(REASON_KELLY_CAP);
        }

        Drawdown drawdown = drawdown(config, request.recentSettledBets());
        if (drawdown.factor() < 1.0) {
            reasons.add(REASON_DRAWDOWN_STOP);
        }
        double afterDrawdown = kellyCappedStake * drawdown.factor();

        double cappedStake = afterDrawdown;
        cappedStake = applyCap(cappedStake, config.maxOpenExposureUnits() - exposure.portfolioUnits(), reasons, REASON_MAX_OPEN_EXPOSURE);
        cappedStake = applyCap(cappedStake, config.perEventCapUnits() - exposure.eventUnits(), reasons, REASON_EVENT_EXPOSURE);
        cappedStake = applyCap(cappedStake, config.perPlayerDailyCapUnits() - exposure.playerUnits(), reasons, REASON_PLAYER_EXPOSURE);
        cappedStake = Math.max(0.0, cappedStake);

        boolean capExhausted = cappedStake <= 0.0
                && (reasons.contains(REASON_MAX_OPEN_EXPOSURE)
                || reasons.contains(REASON_EVENT_EXPOSURE)
                || reasons.contains(REASON_PLAYER_EXPOSURE));
        boolean hardStop = reasons.contains(REASON_EDGE_BELOW_THRESHOLD)
                || reasons.contains(REASON_NEGATIVE_KELLY)
                || reasons.contains(REASON_CORRELATED_OPPOSITE_SIDE)
                || capExhausted;

        if (!hardStop && cappedStake < config.minStakeUnits()) {
            reasons.add(REASON_STAKE_BELOW_MIN);
            hardStop = true;
        }

        StakingDecision.Outcome outcome = hardStop ? StakingDecision.Outcome.NO_BET : StakingDecision.Outcome.BET;
        double stake = outcome == StakingDecision.Outcome.BET ? round4(cappedStake) : 0.0;
        return new StakingDecision(
                outcome,
                stake,
                rawKelly,
                round4(kellyCappedStake),
                round4(afterDrawdown),
                requiredEdge,
                round4(exposure.portfolioUnits()),
                round4(exposure.eventUnits()),
                round4(exposure.playerUnits()),
                drawdown.roi(),
                drawdown.factor(),
                distinct(reasons)
        );
    }

    public double requiredEdge(double decimalOdds) {
        return requiredEdge(configSupplier.get(), decimalOdds);
    }

    static double requiredEdge(StakingPolicyConfig config, double decimalOdds) {
        if (!Double.isFinite(decimalOdds) || decimalOdds <= 1.0) {
            throw new IllegalArgumentException("decimalOdds must be finite and > 1.0");
        }
        return config.minimumEdge() * Math.max(1.0, decimalOdds - 1.0);
    }

    public double rawKellyFraction(double modelProbability, double decimalOdds) {
        if (!Double.isFinite(modelProbability) || modelProbability < 0.0 || modelProbability > 1.0) {
            throw new IllegalArgumentException("modelProbability must lie in [0,1]");
        }
        if (!Double.isFinite(decimalOdds) || decimalOdds <= 1.0) {
            throw new IllegalArgumentException("decimalOdds must be finite and > 1.0");
        }
        double b = decimalOdds - 1.0;
        double q = 1.0 - modelProbability;
        return ((b * modelProbability) - q) / b;
    }

    private Exposure exposure(StakingRequest request) {
        double portfolio = 0.0;
        double event = 0.0;
        double player = 0.0;
        boolean oppositeSide = false;
        Set<Long> requestedPlayers = requestedPlayers(request);

        for (OpenPosition position : request.openPositions()) {
            if (position == null) {
                continue;
            }
            double stake = Math.max(0.0, position.stakeUnits());
            portfolio += stake;
            if (position.sameEvent(request.eventKey())) {
                event += stake;
                oppositeSide = oppositeSide || position.oppositeSide(request.sidePlayerId());
            }
            if (position.sameExposureDate(request.exposureDate())
                    && requestedPlayers.stream().anyMatch(position::involvesPlayer)) {
                player += stake;
            }
        }
        return new Exposure(portfolio, event, player, oppositeSide);
    }

    private Drawdown drawdown(StakingPolicyConfig config, List<SettledStake> recentSettledBets) {
        if (recentSettledBets == null || recentSettledBets.size() < config.drawdownLookbackBets()) {
            return new Drawdown(0.0, 1.0);
        }
        int start = Math.max(0, recentSettledBets.size() - config.drawdownLookbackBets());
        double stake = 0.0;
        double pnl = 0.0;
        for (int i = start; i < recentSettledBets.size(); i++) {
            SettledStake settled = recentSettledBets.get(i);
            if (settled == null) {
                continue;
            }
            stake += settled.stakeUnits();
            pnl += settled.profitLossUnits();
        }
        double roi = stake <= 0.0 ? 0.0 : pnl / stake;
        double factor = roi < config.drawdownTriggerRoi() ? config.drawdownFactor() : 1.0;
        return new Drawdown(round4(roi), factor);
    }

    private Set<Long> requestedPlayers(StakingRequest request) {
        Set<Long> players = new LinkedHashSet<>();
        add(players, request.player1Id());
        add(players, request.player2Id());
        add(players, request.sidePlayerId());
        return players;
    }

    private static void add(Set<Long> players, Long playerId) {
        if (playerId != null) {
            players.add(playerId);
        }
    }

    private static double applyCap(double stake, double remaining, List<String> reasons, String reason) {
        if (remaining <= 0.0) {
            reasons.add(reason);
            return 0.0;
        }
        if (stake > remaining) {
            reasons.add(reason);
            return remaining;
        }
        return stake;
    }

    private static List<String> distinct(List<String> reasons) {
        return new ArrayList<>(new LinkedHashSet<>(reasons));
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private record Exposure(double portfolioUnits,
                            double eventUnits,
                            double playerUnits,
                            boolean hasOppositeSide) { }

    private record Drawdown(double roi, double factor) { }
}
