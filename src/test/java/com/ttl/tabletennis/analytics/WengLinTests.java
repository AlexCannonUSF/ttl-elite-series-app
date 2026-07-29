package com.ttl.tabletennis.analytics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WengLinTests {

    private static final WengLin.Parameters PARAMS = new WengLin.Parameters(1.0, 0.0, 0.05, 1.0, 1.0);

    @Test
    void equalRatingsProduceNeutralProbability() {
        WengLin.Rating a = new WengLin.Rating(0.0, 1.0);
        WengLin.Rating b = new WengLin.Rating(0.0, 1.0);

        assertEquals(0.5, WengLin.winProbability(a, b, PARAMS), 0.0001);
    }

    @Test
    void favoredPlayerHasHigherWinProbability() {
        WengLin.Rating favorite = new WengLin.Rating(2.0, 0.4);
        WengLin.Rating underdog = new WengLin.Rating(-1.0, 0.4);

        assertTrue(WengLin.winProbability(favorite, underdog, PARAMS) > 0.85);
        assertTrue(WengLin.winProbability(underdog, favorite, PARAMS) < 0.15);
    }

    @Test
    void upsetWinMovesBothRatingsAndReducesUncertainty() {
        WengLin.Rating underdog = new WengLin.Rating(-1.0, 0.8);
        WengLin.Rating favorite = new WengLin.Rating(1.5, 0.8);

        WengLin.Update update = WengLin.updateWinner(underdog, favorite, PARAMS);

        assertTrue(update.winner().rating() > underdog.rating());
        assertTrue(update.loser().rating() < favorite.rating());
        assertTrue(update.winner().uncertainty() < underdog.uncertainty());
        assertTrue(update.loser().uncertainty() < favorite.uncertainty());
        assertTrue(update.winnerProbabilityBefore() < 0.5);
    }
}
