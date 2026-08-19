package com.ttl.tabletennis.prediction.conformal;

import com.ttl.tabletennis.prediction.calibration.MondrianGroupKey;

/**
 * Single labelled calibration row for fitting {@link ConformalPredictor}.
 *
 * <ul>
 *   <li>{@code pTop} — the calibrated p_top probability for the matchup
 *       (after Platt + isotonic).</li>
 *   <li>{@code topWon} — {@code true} iff the actual outcome was a top
 *       win. The non-conformity score is {@code 1 - p_hat(true_label)}.
 *   </li>
 *   <li>{@code groupKey} — Mondrian conditioning per Spec §8.3.
 *       {@code null} routes the sample into the pooled bucket only.</li>
 * </ul>
 */
public record ConformalSample(double pTop, boolean topWon, MondrianGroupKey groupKey) {

    public ConformalSample {
        if (Double.isNaN(pTop) || pTop < 0.0 || pTop > 1.0) {
            throw new IllegalArgumentException("pTop must lie in [0, 1]; was " + pTop);
        }
    }

    /** Non-conformity score {@code s(x, y) = 1 - p_hat(true label)} (Spec §8.2). */
    public double nonConformityScore() {
        return topWon ? (1.0 - pTop) : pTop;
    }
}
