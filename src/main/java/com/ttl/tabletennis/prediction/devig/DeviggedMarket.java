package com.ttl.tabletennis.prediction.devig;

/**
 * Three devigged probability estimates for a 2-way market plus the
 * median consensus (Prediction Engine Spec §9.1).
 *
 * <p>Every probability satisfies {@code pTop + pBot = 1} (within float
 * tolerance). {@code overround} is the bookmaker book sum ({@code 1/oTop + 1/oBot}).
 */
public record DeviggedMarket(double decimalOddsTop,
                             double decimalOddsBot,
                             double overround,
                             double pShinTop,
                             double pShinBot,
                             double pPowerTop,
                             double pPowerBot,
                             double pMultTop,
                             double pMultBot,
                             double pConsensusTop,
                             double pConsensusBot,
                             double shinZ,
                             double powerK) {

    public DeviggedMarket {
        validateProbability(pShinTop, "pShinTop");
        validateProbability(pShinBot, "pShinBot");
        validateProbability(pPowerTop, "pPowerTop");
        validateProbability(pPowerBot, "pPowerBot");
        validateProbability(pMultTop, "pMultTop");
        validateProbability(pMultBot, "pMultBot");
        validateProbability(pConsensusTop, "pConsensusTop");
        validateProbability(pConsensusBot, "pConsensusBot");
        if (decimalOddsTop <= 1.0 || decimalOddsBot <= 1.0) {
            throw new IllegalArgumentException("decimal odds must be > 1.0");
        }
        if (overround <= 0.0) {
            throw new IllegalArgumentException("overround must be positive");
        }
    }

    private static void validateProbability(double p, String name) {
        if (Double.isNaN(p) || p < 0.0 || p > 1.0) {
            throw new IllegalArgumentException(name + " must lie in [0, 1]; was " + p);
        }
    }
}
