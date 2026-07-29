package com.ttl.tabletennis.analytics;

public final class RaterEnsemble {

    public static final double GLICKO2_WEIGHT = 0.45;
    public static final double TRUESKILL2_WEIGHT = 0.35;
    public static final double WENGLIN_WEIGHT = 0.20;

    private RaterEnsemble() {
    }

    public static double delta(double glicko2Delta, double trueSkill2Delta, double wengLinDelta) {
        return clampDelta(
                (GLICKO2_WEIGHT * clampDelta(glicko2Delta))
                        + (TRUESKILL2_WEIGHT * clampDelta(trueSkill2Delta))
                        + (WENGLIN_WEIGHT * clampDelta(wengLinDelta))
        );
    }

    public static double probability(double glicko2Probability,
                                     double trueSkill2Probability,
                                     double wengLinProbability) {
        return clampProbability(0.5 + delta(
                clampProbability(glicko2Probability) - 0.5,
                clampProbability(trueSkill2Probability) - 0.5,
                clampProbability(wengLinProbability) - 0.5
        ));
    }

    private static double clampDelta(double delta) {
        if (delta < -0.5) return -0.5;
        if (delta > 0.5) return 0.5;
        return delta;
    }

    private static double clampProbability(double probability) {
        if (probability < 0.0) return 0.0;
        if (probability > 1.0) return 1.0;
        return probability;
    }
}
