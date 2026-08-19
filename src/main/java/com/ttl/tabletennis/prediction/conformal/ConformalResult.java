package com.ttl.tabletennis.prediction.conformal;

import java.util.Set;

/**
 * Output of {@link ConformalPredictor#predict(com.ttl.tabletennis.prediction.calibration.MondrianSplitConformal, double, com.ttl.tabletennis.prediction.calibration.MondrianGroupKey)}.
 *
 * <p>Carries everything {@code Prediction.uncertainty} (Spec §8.4) needs
 * plus the explicit prediction set so consumers don't have to re-derive
 * membership from the label.
 */
public record ConformalResult(double pTop,
                              Label label,
                              Set<Side> predictionSet,
                              double quantile,
                              double alpha,
                              double coverage,
                              double intervalLow,
                              double intervalHigh,
                              String groupKey,
                              String method) {

    public ConformalResult {
        if (Double.isNaN(pTop) || pTop < 0.0 || pTop > 1.0) {
            throw new IllegalArgumentException("pTop must lie in [0, 1]; was " + pTop);
        }
        predictionSet = predictionSet == null ? Set.of() : Set.copyOf(predictionSet);
        groupKey = groupKey == null ? "" : groupKey;
        method = method == null ? "" : method;
    }

    public enum Side { TOP, BOT }

    public enum Label { CONFIDENT_TOP, CONFIDENT_BOT, AMBIGUOUS, ANOMALOUS }
}
