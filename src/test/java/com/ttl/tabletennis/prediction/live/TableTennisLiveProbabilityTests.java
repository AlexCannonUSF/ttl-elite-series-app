package com.ttl.tabletennis.prediction.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableTennisLiveProbabilityTests {

    @Test
    void zeroScoreReproducesPrematchProbability() {
        var estimate = TableTennisLiveProbability.estimate(0.63, 0, 0, 0, 0);
        assertEquals(0.63, estimate.player1MatchProbability(), 1.0e-6);
    }

    @Test
    void scoreLeadershipMovesProbabilityMonotonically() {
        double trailing = TableTennisLiveProbability.estimate(0.55, 0, 2, 5, 8).player1MatchProbability();
        double neutral = TableTennisLiveProbability.estimate(0.55, 1, 1, 5, 5).player1MatchProbability();
        double leading = TableTennisLiveProbability.estimate(0.55, 2, 0, 8, 5).player1MatchProbability();
        assertTrue(trailing < neutral);
        assertTrue(neutral < leading);
    }

    @Test
    void deuceAndAdvantageUseWinByTwoMath() {
        double deuce = TableTennisLiveProbability.estimate(0.50, 2, 2, 10, 10).player1MatchProbability();
        double advantage = TableTennisLiveProbability.estimate(0.50, 2, 2, 11, 10).player1MatchProbability();
        assertEquals(0.50, deuce, 1.0e-6);
        assertTrue(advantage > deuce);
    }

    @Test
    void completedScoreIsEffectivelyCertain() {
        assertEquals(0.999,
                TableTennisLiveProbability.estimate(0.40, 3, 1, null, null).player1MatchProbability());
        assertEquals(0.001,
                TableTennisLiveProbability.estimate(0.60, 1, 3, null, null).player1MatchProbability());
    }
}
