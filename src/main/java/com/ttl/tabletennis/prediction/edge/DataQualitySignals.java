package com.ttl.tabletennis.prediction.edge;

/**
 * Data-quality flags consumed by the edge shrinker.
 *
 * <p>Maps to Prediction Engine Spec §3.8 {@code dq.*} feature names:
 * {@code dq.rater_disagreement_flag} and {@code dq.feature_completeness}.
 * Other DQ flags (mirror disagreement, stream-cv presence) feed the
 * uncertainty label upstream; only these two drive the §9.2 30 % shrink.
 */
public record DataQualitySignals(boolean raterDisagreement,
                                 double featureCompleteness) {

    public static final double DEFAULT_COMPLETENESS = 1.0;

    public DataQualitySignals {
        if (Double.isNaN(featureCompleteness) || featureCompleteness < 0.0 || featureCompleteness > 1.0) {
            throw new IllegalArgumentException(
                    "featureCompleteness must lie in [0, 1]; was " + featureCompleteness);
        }
    }

    public static DataQualitySignals clean() {
        return new DataQualitySignals(false, DEFAULT_COMPLETENESS);
    }
}
