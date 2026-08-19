package com.ttl.tabletennis.prediction.devig;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviggingServiceTests {

    private final DeviggingService devig = new DeviggingService();

    // ---- Fair / symmetric markets -----------------------------------------

    @Test
    void fairMarketReturnsHalfHalfForEveryMethod() {
        DeviggedMarket market = devig.devig(2.00, 2.00);
        // No overround → every method collapses to 0.5/0.5.
        assertEquals(0.5, market.pMultTop(), 1e-9);
        assertEquals(0.5, market.pPowerTop(), 1e-9);
        assertEquals(0.5, market.pShinTop(), 1e-9);
        assertEquals(0.5, market.pConsensusTop(), 1e-9);
        assertEquals(1.0, market.overround(), 1e-9);
    }

    @Test
    void symmetricOverroundIsCenteredOnHalfHalf() {
        DeviggedMarket market = devig.devig(1.95, 1.95);
        // Symmetric overround → every estimator still 0.5/0.5.
        assertEquals(0.5, market.pMultTop(), 1e-9);
        assertEquals(0.5, market.pPowerTop(), 1e-9);
        assertEquals(0.5, market.pShinTop(), 1e-6);
        assertEquals(0.5, market.pConsensusTop(), 1e-6);
        assertTrue(market.overround() > 1.0, "1.95/1.95 has a positive overround");
    }

    // ---- Round-trip identities -------------------------------------------

    @Test
    void everyMethodSumsToOnePerSide() {
        for (double[] odds : new double[][]{{1.50, 2.80}, {1.20, 5.00}, {2.10, 1.80}, {1.95, 1.95}}) {
            DeviggedMarket market = devig.devig(odds[0], odds[1]);
            assertEquals(1.0, market.pMultTop() + market.pMultBot(), 1e-9, "mult sum for " + odds[0] + "/" + odds[1]);
            assertEquals(1.0, market.pPowerTop() + market.pPowerBot(), 1e-6, "power sum for " + odds[0] + "/" + odds[1]);
            assertEquals(1.0, market.pShinTop() + market.pShinBot(), 1e-6, "shin sum for " + odds[0] + "/" + odds[1]);
            assertEquals(1.0, market.pConsensusTop() + market.pConsensusBot(), 1e-9,
                    "consensus sum for " + odds[0] + "/" + odds[1]);
        }
    }

    // ---- Skewed markets ---------------------------------------------------

    @Test
    void skewedFavoriteUnderdog_15_28_returnsSensibleProbabilities() {
        DeviggedMarket market = devig.devig(1.50, 2.80);
        // π_top = 0.6667, π_bot = 0.3571, sum = 1.0238 (positive overround).
        assertEquals(1.0 / 1.5 + 1.0 / 2.8, market.overround(), 1e-9);

        // Multiplicative is the closed form: π_i / sum.
        double expectedMultTop = (1.0 / 1.5) / market.overround();
        assertEquals(expectedMultTop, market.pMultTop(), 1e-9);

        // Power and Shin should land in the same neighbourhood as multiplicative
        // for a 2-way market — within a couple of bp of each other.
        assertTrue(market.pPowerTop() > 0.64 && market.pPowerTop() < 0.66,
                "power pTop out of range: " + market.pPowerTop());
        assertTrue(market.pShinTop() > 0.64 && market.pShinTop() < 0.66,
                "shin pTop out of range: " + market.pShinTop());
    }

    @Test
    void heavilyOverroundMarketStillCalibrates() {
        DeviggedMarket market = devig.devig(1.50, 2.00);
        // π_top = 0.6667, π_bot = 0.5, sum = 1.1667.
        assertEquals(1.0 / 1.5 + 1.0 / 2.0, market.overround(), 1e-9);
        assertTrue(market.pMultTop() > market.pMultBot(), "favorite mult prob should exceed dog");
        assertTrue(market.pPowerTop() > market.pPowerBot(), "favorite power prob should exceed dog");
        assertTrue(market.pShinTop() > market.pShinBot(), "favorite shin prob should exceed dog");
        // Consensus is the median of the three pTop values; must lie in [min, max].
        double maxOfThree = Math.max(market.pMultTop(), Math.max(market.pPowerTop(), market.pShinTop()));
        double minOfThree = Math.min(market.pMultTop(), Math.min(market.pPowerTop(), market.pShinTop()));
        assertTrue(market.pConsensusTop() >= minOfThree - 1e-9);
        assertTrue(market.pConsensusTop() <= maxOfThree + 1e-9);
    }

    // ---- Consensus is median ---------------------------------------------

    @Test
    void consensusIsMedianOfThreeEstimators() {
        DeviggedMarket market = devig.devig(1.50, 2.80);
        double median = DeviggingService.median(market.pMultTop(), market.pPowerTop(), market.pShinTop());
        assertEquals(median, market.pConsensusTop(), 1e-9);
    }

    @Test
    void medianHelperHandlesAllOrderings() {
        // Median of (1, 2, 3) is 2 regardless of input order.
        assertEquals(2.0, DeviggingService.median(1, 2, 3), 1e-12);
        assertEquals(2.0, DeviggingService.median(3, 1, 2), 1e-12);
        assertEquals(2.0, DeviggingService.median(2, 3, 1), 1e-12);
        // Duplicates collapse correctly.
        assertEquals(0.5, DeviggingService.median(0.5, 0.5, 0.7), 1e-12);
        assertEquals(0.4, DeviggingService.median(0.4, 0.4, 0.4), 1e-12);
    }

    // ---- Auditability ----------------------------------------------------

    @Test
    void marketRecordExposesAllThreeEstimatesAndParameters() {
        DeviggedMarket market = devig.devig(1.50, 2.80);
        // The Shin z and Power k must be reasonable for a 2-way overround market.
        assertTrue(market.shinZ() > 0.0 && market.shinZ() < 1.0,
                "shinZ should lie in (0, 1) for an overround market, was " + market.shinZ());
        assertTrue(market.powerK() > 1.0 && market.powerK() < 2.0,
                "powerK should sit just above 1 for a small overround, was " + market.powerK());
    }

    // ---- Validation ------------------------------------------------------

    @Test
    void rejectsNonPositiveOddsAndTooLowOdds() {
        assertThrows(IllegalArgumentException.class, () -> devig.devig(1.0, 2.0));
        assertThrows(IllegalArgumentException.class, () -> devig.devig(2.0, 1.0));
        assertThrows(IllegalArgumentException.class, () -> devig.devig(0.5, 2.0));
        assertThrows(IllegalArgumentException.class, () -> devig.devig(-1.0, 2.0));
    }

    // ---- Internal solver sanity ------------------------------------------

    @Test
    void powerSolverReturnsKOneForUnitSum() {
        DeviggingService.PowerResult result = devig.power(0.5, 0.5);
        assertEquals(1.0, result.k(), 1e-9);
        assertEquals(0.5, result.pTop(), 1e-9);
    }

    @Test
    void shinSolverReturnsZeroForUnitSum() {
        DeviggingService.ShinResult result = devig.shin(0.5, 0.5);
        assertEquals(0.0, result.z(), 1e-9);
        assertEquals(0.5, result.pTop(), 1e-9);
    }
}
