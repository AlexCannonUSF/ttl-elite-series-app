package com.ttl.tabletennis.analytics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrueSkill2Tests {

    private static final TrueSkill2.Parameters PARAMS = new TrueSkill2.Parameters(
            25.0 / 6.0,
            0.0,
            0.75,
            25.0 / 3.0
    );

    @Test
    void equalRatingsProduceNeutralProbability() {
        TrueSkill2.Rating a = new TrueSkill2.Rating(25.0, 25.0 / 3.0);
        TrueSkill2.Rating b = new TrueSkill2.Rating(25.0, 25.0 / 3.0);

        assertEquals(0.5, TrueSkill2.winProbability(a, b, PARAMS), 0.0001);
    }

    @Test
    void favoredPlayerHasHigherWinProbability() {
        TrueSkill2.Rating favorite = new TrueSkill2.Rating(31.0, 3.0);
        TrueSkill2.Rating underdog = new TrueSkill2.Rating(21.0, 3.0);

        assertTrue(TrueSkill2.winProbability(favorite, underdog, PARAMS) > 0.8);
        assertTrue(TrueSkill2.winProbability(underdog, favorite, PARAMS) < 0.2);
    }

    @Test
    void upsetWinMovesBothRatingsAndReducesUncertainty() {
        TrueSkill2.Rating underdog = new TrueSkill2.Rating(20.0, 5.0);
        TrueSkill2.Rating favorite = new TrueSkill2.Rating(30.0, 5.0);

        TrueSkill2.Update update = TrueSkill2.updateWinner(underdog, favorite, PARAMS);

        assertTrue(update.winner().mu() > underdog.mu());
        assertTrue(update.loser().mu() < favorite.mu());
        assertTrue(update.winner().sigma() < underdog.sigma());
        assertTrue(update.loser().sigma() < favorite.sigma());
        assertTrue(update.winnerProbabilityBefore() < 0.5);
    }
}
