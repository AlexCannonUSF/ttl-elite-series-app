package com.ttl.tabletennis.prediction.staking;

public record SettledStake(double stakeUnits, double profitLossUnits) {

    public SettledStake {
        if (!Double.isFinite(stakeUnits) || stakeUnits < 0.0) {
            throw new IllegalArgumentException("stakeUnits must be finite and non-negative");
        }
        if (!Double.isFinite(profitLossUnits)) {
            throw new IllegalArgumentException("profitLossUnits must be finite");
        }
    }
}
