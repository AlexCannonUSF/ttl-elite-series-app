package com.ttl.tabletennis.prediction.edge;

import java.util.List;

/**
 * Result of combining a model probability with a devigged market.
 * Maps to the {@code Edge} field on {@code Prediction} per
 * Prediction Engine Spec §10.
 *
 * <p>{@code shrinkFactor} is the product of every shrinker that fired
 * ({@code 0.7} per data-quality strike, {@code 0.5} for AMBIGUOUS /
 * ANOMALOUS uncertainty per §9.2). {@code appliedShrinkers} carries the
 * audit trail so consumers can explain why an edge was attenuated.
 */
public record Edge(double pModelTop,
                   double pModelBot,
                   double pFairTop,
                   double pFairBot,
                   double rawEdgeTop,
                   double rawEdgeBot,
                   double edgeTop,
                   double edgeBot,
                   double shrinkFactor,
                   List<String> appliedShrinkers,
                   String uncertaintyLabel) {

    public Edge {
        validateProbability(pModelTop, "pModelTop");
        validateProbability(pModelBot, "pModelBot");
        validateProbability(pFairTop, "pFairTop");
        validateProbability(pFairBot, "pFairBot");
        if (shrinkFactor < 0.0 || shrinkFactor > 1.0) {
            throw new IllegalArgumentException("shrinkFactor must lie in [0, 1]; was " + shrinkFactor);
        }
        appliedShrinkers = appliedShrinkers == null ? List.of() : List.copyOf(appliedShrinkers);
        uncertaintyLabel = uncertaintyLabel == null ? "UNKNOWN" : uncertaintyLabel.trim();
    }

    public boolean hasAttenuation() {
        return shrinkFactor < 1.0;
    }

    private static void validateProbability(double p, String name) {
        if (Double.isNaN(p) || p < 0.0 || p > 1.0) {
            throw new IllegalArgumentException(name + " must lie in [0, 1]; was " + p);
        }
    }
}
