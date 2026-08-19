package com.ttl.tabletennis.prediction.live;

import java.util.HashMap;
import java.util.Map;

/**
 * Score-conditioned best-of-five table-tennis probability calculator.
 * Sportsbook prices are intentionally absent from this API: prices are a
 * comparison target for edge, never an input into the model probability.
 */
public final class TableTennisLiveProbability {

    private static final int TARGET_SETS = 3;

    private TableTennisLiveProbability() {
    }

    public static Estimate estimate(double prematchMatchProbability,
                                    Integer setsP1,
                                    Integer setsP2,
                                    Integer pointsP1,
                                    Integer pointsP2) {
        double prematch = clamp(prematchMatchProbability, 0.01, 0.99);
        int leftSets = validNonNegative(setsP1) ? setsP1 : 0;
        int rightSets = validNonNegative(setsP2) ? setsP2 : 0;
        if (leftSets >= TARGET_SETS || rightSets >= TARGET_SETS) {
            return new Estimate(leftSets >= TARGET_SETS ? 0.999 : 0.001, true, "FINAL_SCORE");
        }

        double setWinProbability = invertBestOfFive(prematch);
        boolean hasSetScore = setsP1 != null && setsP2 != null;
        boolean hasPointScore = validNonNegative(pointsP1) && validNonNegative(pointsP2);
        double currentSetProbability = setWinProbability;
        String method = hasSetScore ? "SET_SCORE" : "PREMATCH_ONLY";
        if (hasPointScore) {
            double pointWinProbability = invertGameToEleven(setWinProbability);
            currentSetProbability = gameProbability(pointsP1, pointsP2, pointWinProbability, new HashMap<>());
            method = "SET_AND_POINT_SCORE";
        }
        if (!hasSetScore && !hasPointScore) {
            return new Estimate(prematch, false, method);
        }
        return new Estimate(
                clamp(matchProbability(leftSets, rightSets, currentSetProbability, setWinProbability), 0.001, 0.999),
                true,
                method
        );
    }

    static double gameProbability(int p1Points, int p2Points, double pointWinProbability,
                                  Map<Long, Double> memo) {
        int left = Math.max(0, p1Points);
        int right = Math.max(0, p2Points);
        double p = clamp(pointWinProbability, 0.001, 0.999);
        if ((left >= 11 || right >= 11) && Math.abs(left - right) >= 2) {
            return left > right ? 1.0 : 0.0;
        }
        double deuce = (p * p) / ((p * p) + ((1.0 - p) * (1.0 - p)));
        if (left >= 10 && right >= 10) {
            if (left == right) return deuce;
            if (left == right + 1) return p + ((1.0 - p) * deuce);
            if (right == left + 1) return p * deuce;
        }
        if (left == 10 && right == 9) return p + ((1.0 - p) * deuce);
        if (right == 10 && left == 9) return p * deuce;

        long key = (((long) left) << 32) | (right & 0xffffffffL);
        Double cached = memo.get(key);
        if (cached != null) return cached;
        double result = p * gameProbability(left + 1, right, p, memo)
                + (1.0 - p) * gameProbability(left, right + 1, p, memo);
        memo.put(key, result);
        return result;
    }

    private static double matchProbability(int setsP1, int setsP2,
                                           double currentSetProbability,
                                           double futureSetProbability) {
        return currentSetProbability * remainingMatchProbability(setsP1 + 1, setsP2, futureSetProbability)
                + (1.0 - currentSetProbability)
                * remainingMatchProbability(setsP1, setsP2 + 1, futureSetProbability);
    }

    private static double remainingMatchProbability(int setsP1, int setsP2, double setWinProbability) {
        if (setsP1 >= TARGET_SETS) return 1.0;
        if (setsP2 >= TARGET_SETS) return 0.0;
        return setWinProbability * remainingMatchProbability(setsP1 + 1, setsP2, setWinProbability)
                + (1.0 - setWinProbability)
                * remainingMatchProbability(setsP1, setsP2 + 1, setWinProbability);
    }

    private static double invertBestOfFive(double matchProbability) {
        return invert(matchProbability, candidate -> {
            double q = 1.0 - candidate;
            return Math.pow(candidate, 3.0)
                    + (3.0 * Math.pow(candidate, 3.0) * q)
                    + (6.0 * Math.pow(candidate, 3.0) * q * q);
        });
    }

    private static double invertGameToEleven(double setProbability) {
        return invert(setProbability, candidate -> gameProbability(0, 0, candidate, new HashMap<>()));
    }

    private static double invert(double target, ProbabilityFunction function) {
        double low = 0.001;
        double high = 0.999;
        for (int i = 0; i < 70; i++) {
            double mid = (low + high) / 2.0;
            if (function.apply(mid) < target) low = mid;
            else high = mid;
        }
        return (low + high) / 2.0;
    }

    private static boolean validNonNegative(Integer value) {
        return value != null && value >= 0;
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    @FunctionalInterface
    private interface ProbabilityFunction {
        double apply(double probability);
    }

    public record Estimate(double player1MatchProbability, boolean scoreConditioned, String method) {
    }
}
