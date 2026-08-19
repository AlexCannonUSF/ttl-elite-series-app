package com.ttl.tabletennis.prediction.staking;

import java.util.List;

public record StakingDecision(
        Outcome outcome,
        double stakeUnits,
        double rawKellyFraction,
        double fractionalKellyStakeUnits,
        double afterDrawdownUnits,
        double requiredEdge,
        double portfolioExposureBeforeUnits,
        double eventExposureBeforeUnits,
        double playerExposureBeforeUnits,
        double drawdownRoi,
        double drawdownFactor,
        List<String> reasonCodes
) {

    public enum Outcome {
        BET,
        NO_BET
    }

    public StakingDecision {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
        stakeUnits = nonNegative(stakeUnits, "stakeUnits");
        fractionalKellyStakeUnits = nonNegative(fractionalKellyStakeUnits, "fractionalKellyStakeUnits");
        afterDrawdownUnits = nonNegative(afterDrawdownUnits, "afterDrawdownUnits");
        requiredEdge = nonNegative(requiredEdge, "requiredEdge");
        portfolioExposureBeforeUnits = nonNegative(portfolioExposureBeforeUnits, "portfolioExposureBeforeUnits");
        eventExposureBeforeUnits = nonNegative(eventExposureBeforeUnits, "eventExposureBeforeUnits");
        playerExposureBeforeUnits = nonNegative(playerExposureBeforeUnits, "playerExposureBeforeUnits");
        if (!Double.isFinite(rawKellyFraction)) {
            throw new IllegalArgumentException("rawKellyFraction must be finite");
        }
        if (!Double.isFinite(drawdownRoi)) {
            throw new IllegalArgumentException("drawdownRoi must be finite");
        }
        if (!Double.isFinite(drawdownFactor) || drawdownFactor < 0.0 || drawdownFactor > 1.0) {
            throw new IllegalArgumentException("drawdownFactor must lie in [0,1]");
        }
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
    }

    public boolean isBet() {
        return outcome == Outcome.BET;
    }

    private static double nonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
        return value;
    }
}
