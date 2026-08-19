package com.ttl.tabletennis.prediction.devig;

import org.springframework.stereotype.Service;

/**
 * Devigging service per Prediction Engine Spec §9.1.
 *
 * <p>Given decimal odds for the two outcomes of a 2-way market, returns
 * three independent fair-probability estimates plus the per-side median
 * consensus:
 *
 * <ul>
 *   <li><b>Multiplicative</b> — {@code p_dev_i = π_i / sum(π_j)}. Cheap
 *   baseline; ignores asymmetric overround.</li>
 *   <li><b>Power</b> — {@code p_dev_i = π_i^k} with {@code k} chosen so
 *   the dev probs sum to 1. Good when overround is symmetric.</li>
 *   <li><b>Shin</b> — solves the Shin (1992) insider-trader correction
 *   for parameter {@code z} and recovers fair probabilities. Good when
 *   overround leans toward one side.</li>
 * </ul>
 *
 * <p>All methods are deterministic and pure — safe to call from request
 * paths.
 */
@Service
public class DeviggingService {

    static final int MAX_ITERATIONS = 80;
    static final double TOLERANCE = 1.0e-9;

    public DeviggedMarket devig(double decimalOddsTop, double decimalOddsBot) {
        if (decimalOddsTop <= 1.0 || decimalOddsBot <= 1.0) {
            throw new IllegalArgumentException("decimal odds must be > 1.0; got "
                    + decimalOddsTop + " / " + decimalOddsBot);
        }
        double piTop = 1.0 / decimalOddsTop;
        double piBot = 1.0 / decimalOddsBot;
        double overround = piTop + piBot;
        if (overround <= 0.0 || !Double.isFinite(overround)) {
            throw new IllegalArgumentException("derived overround must be positive and finite");
        }

        double pMultTop = piTop / overround;
        double pMultBot = piBot / overround;

        PowerResult power = power(piTop, piBot);
        ShinResult shin = shin(piTop, piBot);

        double consensusTop = median(shin.pTop(), power.pTop(), pMultTop);
        double consensusBot = 1.0 - consensusTop;

        return new DeviggedMarket(
                decimalOddsTop,
                decimalOddsBot,
                overround,
                shin.pTop(), shin.pBot(),
                power.pTop(), power.pBot(),
                pMultTop, pMultBot,
                consensusTop, consensusBot,
                shin.z(),
                power.k()
        );
    }

    // ---- Power method --------------------------------------------------

    PowerResult power(double piTop, double piBot) {
        double sum = piTop + piBot;
        if (Math.abs(sum - 1.0) < TOLERANCE) {
            return new PowerResult(piTop, piBot, 1.0);
        }
        // Find k such that piTop^k + piBot^k = 1.
        // For overround > 1: piTop + piBot > 1 → need k < 1.
        // For "overround" < 1 (rare; bookmaker pays out): k > 1.
        double lo = 1.0e-9;
        double hi = 50.0;
        double f = powerSum(piTop, piBot, hi) - 1.0;
        // f(0+) → 2 - 1 = 1 (positive); f(+∞) → 0 - 1 = -1 (negative); monotone decreasing in k.
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            double mid = 0.5 * (lo + hi);
            double sumAtMid = powerSum(piTop, piBot, mid);
            if (Math.abs(sumAtMid - 1.0) < TOLERANCE) {
                return new PowerResult(Math.pow(piTop, mid), Math.pow(piBot, mid), mid);
            }
            if (sumAtMid > 1.0) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double k = 0.5 * (lo + hi);
        return new PowerResult(Math.pow(piTop, k), Math.pow(piBot, k), k);
    }

    private double powerSum(double piTop, double piBot, double k) {
        return Math.pow(piTop, k) + Math.pow(piBot, k);
    }

    // ---- Shin method ---------------------------------------------------

    ShinResult shin(double piTop, double piBot) {
        double sum = piTop + piBot;
        if (Math.abs(sum - 1.0) < TOLERANCE) {
            return new ShinResult(piTop, piBot, 0.0);
        }
        // Solve for z in [0, 1) such that:
        //   sqrt(z² + 4(1-z)·π_top²/B) + sqrt(z² + 4(1-z)·π_bot²/B) = 2
        // where B = π_top + π_bot. Then p_fair_i = ( -z + sqrt(...)) / (2(1-z)).
        //
        // The LHS at z=0 equals 2·sqrt(π_top² + π_bot²) / sqrt(B); for B>1 (overround) this is >2,
        // and the LHS strictly decreases in z, hitting 2 somewhere in (0, 1).
        double lo = 0.0;
        double hi = 0.9999999;
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            double mid = 0.5 * (lo + hi);
            double sumAtMid = shinSum(piTop, piBot, sum, mid);
            if (Math.abs(sumAtMid - 2.0) < TOLERANCE) {
                return shinResult(piTop, piBot, sum, mid);
            }
            if (sumAtMid > 2.0) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double z = 0.5 * (lo + hi);
        return shinResult(piTop, piBot, sum, z);
    }

    private double shinSum(double piTop, double piBot, double bookSum, double z) {
        double a = z * z + 4.0 * (1.0 - z) * (piTop * piTop) / bookSum;
        double b = z * z + 4.0 * (1.0 - z) * (piBot * piBot) / bookSum;
        return Math.sqrt(a) + Math.sqrt(b);
    }

    private ShinResult shinResult(double piTop, double piBot, double bookSum, double z) {
        double denom = 2.0 * (1.0 - z);
        if (denom <= 0.0) {
            // Degenerate; fall back to multiplicative.
            return new ShinResult(piTop / bookSum, piBot / bookSum, z);
        }
        double pTop = (-z + Math.sqrt(z * z + 4.0 * (1.0 - z) * (piTop * piTop) / bookSum)) / denom;
        double pBot = (-z + Math.sqrt(z * z + 4.0 * (1.0 - z) * (piBot * piBot) / bookSum)) / denom;
        double sum = pTop + pBot;
        if (sum > 0.0) {
            pTop = pTop / sum;
            pBot = pBot / sum;
        }
        return new ShinResult(pTop, pBot, z);
    }

    // ---- Helpers -------------------------------------------------------

    static double median(double a, double b, double c) {
        double max = Math.max(a, Math.max(b, c));
        double min = Math.min(a, Math.min(b, c));
        return a + b + c - max - min;
    }

    record PowerResult(double pTop, double pBot, double k) { }
    record ShinResult(double pTop, double pBot, double z) { }
}
