package com.ttl.tabletennis.prediction.staking;

public record StakingPolicyConfig(
        double fractionalKelly,
        double kellyCapUnits,
        double perEventCapUnits,
        double perPlayerDailyCapUnits,
        double maxOpenExposureUnits,
        double minStakeUnits,
        double minimumEdge,
        int drawdownLookbackBets,
        double drawdownTriggerRoi,
        double drawdownFactor
) {

    public static StakingPolicyConfig defaults() {
        return new StakingPolicyConfig(
                0.25,
                1.5,
                2.0,
                1.5,
                5.0,
                0.1,
                0.025,
                50,
                -0.08,
                0.50
        );
    }

    public StakingPolicyConfig {
        fractionalKelly = requireRange(fractionalKelly, 0.0, 1.0, "fractionalKelly");
        kellyCapUnits = requirePositive(kellyCapUnits, "kellyCapUnits");
        perEventCapUnits = requirePositive(perEventCapUnits, "perEventCapUnits");
        perPlayerDailyCapUnits = requirePositive(perPlayerDailyCapUnits, "perPlayerDailyCapUnits");
        maxOpenExposureUnits = requirePositive(maxOpenExposureUnits, "maxOpenExposureUnits");
        minStakeUnits = requirePositive(minStakeUnits, "minStakeUnits");
        minimumEdge = requireRange(minimumEdge, 0.0, 1.0, "minimumEdge");
        if (drawdownLookbackBets < 1) {
            throw new IllegalArgumentException("drawdownLookbackBets must be >= 1");
        }
        if (!Double.isFinite(drawdownTriggerRoi) || drawdownTriggerRoi >= 0.0) {
            throw new IllegalArgumentException("drawdownTriggerRoi must be finite and negative");
        }
        drawdownFactor = requireRange(drawdownFactor, 0.0, 1.0, "drawdownFactor");
    }

    private static double requirePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
        return value;
    }

    private static double requireRange(double value, double min, double max, String name) {
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(name + " must lie in [" + min + "," + max + "]");
        }
        return value;
    }
}
