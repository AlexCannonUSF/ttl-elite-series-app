package com.ttl.tabletennis.prediction.edge;

import com.ttl.tabletennis.prediction.devig.DeviggedMarket;
import com.ttl.tabletennis.prediction.devig.DeviggingService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdgeEngineTests {

    private final EdgeEngine engine = new EdgeEngine();
    private final DeviggingService devig = new DeviggingService();

    @Test
    void cleanInputsProduceUnshrunkEdge() {
        DeviggedMarket market = devig.devig(1.50, 2.80);
        Edge edge = engine.compute(0.70, market, DataQualitySignals.clean(), "CONFIDENT_TOP");

        assertEquals(1.0, edge.shrinkFactor(), 1e-9);
        assertEquals(edge.rawEdgeTop(), edge.edgeTop(), 1e-9);
        assertTrue(edge.appliedShrinkers().isEmpty());
        assertFalse(edge.hasAttenuation());
    }

    @Test
    void raterDisagreementShrinksBy30Percent() {
        DeviggedMarket market = devig.devig(1.50, 2.80);
        Edge edge = engine.compute(0.70, market,
                new DataQualitySignals(true, 1.0),
                "CONFIDENT_TOP");

        assertEquals(EdgeEngine.DQ_KEEP, edge.shrinkFactor(), 1e-9);
        assertEquals(edge.rawEdgeTop() * EdgeEngine.DQ_KEEP, edge.edgeTop(), 1e-12);
        assertTrue(edge.appliedShrinkers().contains(EdgeEngine.SHRINK_RATER_DISAGREEMENT));
    }

    @Test
    void lowFeatureCompletenessShrinksBy30Percent() {
        DeviggedMarket market = devig.devig(1.50, 2.80);
        Edge edge = engine.compute(0.70, market,
                new DataQualitySignals(false, 0.75),
                "CONFIDENT_TOP");

        assertEquals(EdgeEngine.DQ_KEEP, edge.shrinkFactor(), 1e-9);
        assertTrue(edge.appliedShrinkers().contains(EdgeEngine.SHRINK_FEATURE_COMPLETENESS));
    }

    @Test
    void featureCompletenessAtFloorDoesNotTrigger() {
        DeviggedMarket market = devig.devig(1.50, 2.80);
        Edge edge = engine.compute(0.70, market,
                new DataQualitySignals(false, EdgeEngine.FEATURE_COMPLETENESS_FLOOR),
                "CONFIDENT_TOP");

        assertEquals(1.0, edge.shrinkFactor(), 1e-9);
    }

    @Test
    void bothDqStrikesAreReportedButCountAsOneShrink() {
        DeviggedMarket market = devig.devig(1.50, 2.80);
        Edge edge = engine.compute(0.70, market,
                new DataQualitySignals(true, 0.6),
                "CONFIDENT_TOP");

        // §9.2 reads as a single 30 % shrink "under" the OR condition.
        assertEquals(EdgeEngine.DQ_KEEP, edge.shrinkFactor(), 1e-9);
        assertTrue(edge.appliedShrinkers().contains(EdgeEngine.SHRINK_RATER_DISAGREEMENT));
        assertTrue(edge.appliedShrinkers().contains(EdgeEngine.SHRINK_FEATURE_COMPLETENESS));
    }

    @Test
    void ambiguousLabelShrinksBy50Percent() {
        DeviggedMarket market = devig.devig(1.50, 2.80);
        Edge edge = engine.compute(0.70, market, DataQualitySignals.clean(), "AMBIGUOUS");
        assertEquals(EdgeEngine.UNCERTAINTY_KEEP, edge.shrinkFactor(), 1e-9);
        assertTrue(edge.appliedShrinkers().contains(EdgeEngine.SHRINK_UNCERTAINTY_AMBIGUOUS));
    }

    @Test
    void anomalousLabelShrinksBy50Percent() {
        DeviggedMarket market = devig.devig(1.50, 2.80);
        Edge edge = engine.compute(0.70, market, DataQualitySignals.clean(), "ANOMALOUS");
        assertEquals(EdgeEngine.UNCERTAINTY_KEEP, edge.shrinkFactor(), 1e-9);
        assertTrue(edge.appliedShrinkers().contains(EdgeEngine.SHRINK_UNCERTAINTY_ANOMALOUS));
    }

    @Test
    void bothStrikesMultiplyShrinkers() {
        DeviggedMarket market = devig.devig(1.50, 2.80);
        Edge edge = engine.compute(0.70, market,
                new DataQualitySignals(true, 1.0),
                "AMBIGUOUS");

        assertEquals(EdgeEngine.DQ_KEEP * EdgeEngine.UNCERTAINTY_KEEP, edge.shrinkFactor(), 1e-9);
    }

    @Test
    void confidentBotAndConfidentTopBothLeaveEdgeUnshrunk() {
        DeviggedMarket market = devig.devig(1.50, 2.80);
        for (String label : new String[]{"CONFIDENT_TOP", "CONFIDENT_BOT", "UNKNOWN", null}) {
            Edge edge = engine.compute(0.70, market, DataQualitySignals.clean(), label);
            assertEquals(1.0, edge.shrinkFactor(), 1e-9, "label=" + label);
        }
    }

    @Test
    void labelLookupIsCaseInsensitive() {
        DeviggedMarket market = devig.devig(1.50, 2.80);
        Edge lower = engine.compute(0.70, market, DataQualitySignals.clean(), "ambiguous");
        assertEquals(EdgeEngine.UNCERTAINTY_KEEP, lower.shrinkFactor(), 1e-9);
    }

    @Test
    void negativeEdgeStaysNegativeAndScales() {
        DeviggedMarket market = devig.devig(1.50, 2.80);
        // p_fair ≈ 0.65; model says 0.40 → negative edge on top.
        Edge edge = engine.compute(0.40, market,
                new DataQualitySignals(true, 1.0),
                "AMBIGUOUS");
        assertTrue(edge.rawEdgeTop() < 0.0, "raw edge should be negative");
        assertTrue(edge.edgeTop() < 0.0, "shrunk edge should still be negative");
        assertEquals(edge.rawEdgeTop() * edge.shrinkFactor(), edge.edgeTop(), 1e-12);
        // Sides sum to zero by construction since pBot = 1 - pTop and same for fair.
        assertEquals(0.0, edge.rawEdgeTop() + edge.rawEdgeBot(), 1e-9);
    }

    @Test
    void edgeRecordCarriesAuditTrail() {
        DeviggedMarket market = devig.devig(1.50, 2.80);
        Edge edge = engine.compute(0.70, market,
                new DataQualitySignals(true, 0.5),
                "ANOMALOUS");
        assertEquals(3, edge.appliedShrinkers().size());
        assertTrue(edge.appliedShrinkers().contains(EdgeEngine.SHRINK_RATER_DISAGREEMENT));
        assertTrue(edge.appliedShrinkers().contains(EdgeEngine.SHRINK_FEATURE_COMPLETENESS));
        assertTrue(edge.appliedShrinkers().contains(EdgeEngine.SHRINK_UNCERTAINTY_ANOMALOUS));
        assertEquals("ANOMALOUS", edge.uncertaintyLabel());
    }

    @Test
    void nullMarketAndOutOfRangeProbabilityRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> engine.compute(0.5, null, DataQualitySignals.clean(), "CONFIDENT_TOP"));
        DeviggedMarket market = devig.devig(2.0, 2.0);
        assertThrows(IllegalArgumentException.class,
                () -> engine.compute(1.5, market, DataQualitySignals.clean(), "CONFIDENT_TOP"));
        assertThrows(IllegalArgumentException.class,
                () -> engine.compute(-0.1, market, DataQualitySignals.clean(), "CONFIDENT_TOP"));
    }

    @Test
    void dataQualityCompletenessOutOfRangeRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DataQualitySignals(false, -0.1));
        assertThrows(IllegalArgumentException.class, () -> new DataQualitySignals(false, 1.1));
    }

    @Test
    void edgeBotMirrorsEdgeTopAroundFairBook() {
        DeviggedMarket market = devig.devig(1.50, 2.80);
        Edge edge = engine.compute(0.70, market, DataQualitySignals.clean(), "CONFIDENT_TOP");
        // Because both sides come from the same shrink factor, edgeTop ≈ -edgeBot
        // up to rounding (since pModelTop + pModelBot = 1 = pFairTop + pFairBot).
        assertEquals(-edge.edgeTop(), edge.edgeBot(), 1e-9);
    }
}
