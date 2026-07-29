package com.ttl.tabletennis.analytics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaterEnsembleTests {

    @Test
    void combinesDeltasWithPinnedPhase05Weights() {
        double delta = RaterEnsemble.delta(0.10, 0.20, -0.05);

        assertEquals(0.105, delta, 0.000001);
    }

    @Test
    void probabilityConvertsWeightedDeltaBackToProbability() {
        double probability = RaterEnsemble.probability(0.60, 0.70, 0.45);

        assertEquals(0.605, probability, 0.000001);
        assertTrue(probability > 0.5);
    }
}
