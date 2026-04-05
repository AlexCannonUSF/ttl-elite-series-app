package com.ttl.tabletennis.analytics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Glicko2Tests {

    @Test
    void updateMatchesReferenceExample() {
        Glicko2.Rating current = new Glicko2.Rating(1500.0, 200.0, 0.06);
        List<Glicko2.OpponentResult> results = List.of(
                new Glicko2.OpponentResult(1400.0, 30.0, 1.0),
                new Glicko2.OpponentResult(1550.0, 100.0, 0.0),
                new Glicko2.OpponentResult(1700.0, 300.0, 0.0)
        );

        Glicko2.Rating updated = Glicko2.update(current, results, 0.5);

        assertEquals(1464.06, updated.rating(), 0.75);
        assertEquals(151.52, updated.ratingDeviation(), 0.75);
        assertEquals(0.05999, updated.volatility(), 0.001);
    }

    @Test
    void expectedScoreReflectsRatingAdvantage() {
        double pEven = Glicko2.expectedScore(1500, 200, 1500, 200);
        double pFavored = Glicko2.expectedScore(1650, 80, 1500, 80);

        assertEquals(0.5, pEven, 0.03);
        assertTrue(pFavored > 0.5);
    }
}
