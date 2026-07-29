package com.ttl.tabletennis.prediction.conformal;

import com.ttl.tabletennis.prediction.calibration.MondrianGroupKey;
import com.ttl.tabletennis.prediction.calibration.MondrianSplitConformal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConformalPredictorTests {

    private final ConformalPredictor predictor = new ConformalPredictor();

    // ---- Quantile formula ------------------------------------------------

    @Test
    void splitQuantileMatchesSpecFormula() {
        // n=99, alpha=0.1 → rank = ceil(100 * 0.9) = 90 → arr[89]
        List<Double> scores = new ArrayList<>();
        for (int i = 1; i < 100; i++) {
            scores.add(i / 100.0);
        }
        double q = predictor.splitQuantile(scores, 0.1);
        assertEquals(0.90, q, 1e-9);
    }

    @Test
    void splitQuantileClampsRankToN() {
        // n=4, alpha=0.01 → rank = ceil(5*0.99) = 5 → clamp to 4
        double q = predictor.splitQuantile(Arrays.asList(0.1, 0.2, 0.3, 0.4), 0.01);
        assertEquals(0.4, q, 1e-9);
    }

    @Test
    void splitQuantileClampsRankToOne() {
        // n=4, alpha=0.999 → rank = ceil(5*0.001) ~ 1 → clamp to 1
        double q = predictor.splitQuantile(Arrays.asList(0.1, 0.2, 0.3, 0.4), 0.999);
        assertEquals(0.1, q, 1e-9);
    }

    @Test
    void emptyScoresReturnsOne() {
        assertEquals(1.0, predictor.splitQuantile(List.of(), 0.1), 1e-9);
    }

    // ---- Fit -------------------------------------------------------------

    @Test
    void fitProducesPerGroupQuantilesWhenSamplesPerGroupExceedMin() {
        List<ConformalSample> samples = new ArrayList<>();
        MondrianGroupKey g3 = new MondrianGroupKey(3, false, true);
        MondrianGroupKey g5 = new MondrianGroupKey(5, true, false);

        // Group 3: 100 samples with score distribution centred at 0.2
        Random rng = new Random(7);
        for (int i = 0; i < 100; i++) {
            double p = clamp01(rng.nextGaussian() * 0.05 + 0.8);
            boolean topWon = rng.nextDouble() < p;
            samples.add(new ConformalSample(p, topWon, g3));
        }
        // Group 5: 50 samples centred at higher uncertainty
        for (int i = 0; i < 50; i++) {
            double p = clamp01(rng.nextGaussian() * 0.1 + 0.5);
            boolean topWon = rng.nextDouble() < p;
            samples.add(new ConformalSample(p, topWon, g5));
        }

        MondrianSplitConformal model = predictor.fit(samples, 0.1, 30, "test-v1");
        assertEquals(0.1, model.alpha(), 1e-12);
        assertEquals("test-v1", model.version());
        assertEquals(100, (int) model.counts().get(g3.encode()));
        assertEquals(50, (int) model.counts().get(g5.encode()));
        assertTrue(model.quantiles().containsKey(g3.encode()));
        assertTrue(model.quantiles().containsKey(g5.encode()));
    }

    @Test
    void fitFallsBackToPooledForSmallGroups() {
        List<ConformalSample> samples = new ArrayList<>();
        MondrianGroupKey big = new MondrianGroupKey(3, false, true);
        MondrianGroupKey tiny = new MondrianGroupKey(5, false, false);
        Random rng = new Random(11);

        for (int i = 0; i < 200; i++) {
            double p = clamp01(rng.nextDouble());
            samples.add(new ConformalSample(p, p > 0.5, big));
        }
        for (int i = 0; i < 5; i++) {
            samples.add(new ConformalSample(0.5, true, tiny));
        }

        MondrianSplitConformal model = predictor.fit(samples, 0.1, 30, "v");
        assertEquals(model.fallbackQuantile(), model.quantileFor(tiny.encode()), 1e-12);
    }

    @Test
    void fitRejectsInvalidAlpha() {
        assertThrows(IllegalArgumentException.class,
                () -> predictor.fit(List.of(), 0.0, 30, "v"));
        assertThrows(IllegalArgumentException.class,
                () -> predictor.fit(List.of(), 1.0, 30, "v"));
    }

    @Test
    void fitWithNullSamplesProducesFallbackOnlyModel() {
        MondrianSplitConformal model = predictor.fit(null, 0.1, 30, "v");
        assertEquals(0.1, model.alpha(), 1e-12);
        assertTrue(model.quantiles().isEmpty());
    }

    // ---- Predict (apply) -------------------------------------------------

    @Test
    void predictBinaryLabelsCoverTheFourCases() {
        MondrianSplitConformal model = new MondrianSplitConformal(
                0.1, 0.6, Map.of(), Map.of(), "v"
        );
        MondrianGroupKey key = new MondrianGroupKey(3, false, false);

        // q̂ = 0.6 → top in iff p ≥ 0.4; bot in iff p ≤ 0.6.
        assertEquals(ConformalResult.Label.CONFIDENT_TOP,
                predictor.predict(model, 0.85, key).label());
        assertEquals(ConformalResult.Label.CONFIDENT_BOT,
                predictor.predict(model, 0.15, key).label());
        assertEquals(ConformalResult.Label.AMBIGUOUS,
                predictor.predict(model, 0.5, key).label());
    }

    @Test
    void predictAnomalousWhenQuantileBelowHalf() {
        MondrianSplitConformal model = new MondrianSplitConformal(
                0.1, 0.3, Map.of(), Map.of(), "v"
        );
        MondrianGroupKey key = new MondrianGroupKey(3, false, false);
        ConformalResult result = predictor.predict(model, 0.5, key);
        assertEquals(ConformalResult.Label.ANOMALOUS, result.label());
        assertTrue(result.predictionSet().isEmpty());
    }

    @Test
    void predictPredictionSetCarriesActualMembership() {
        MondrianSplitConformal model = new MondrianSplitConformal(
                0.1, 0.6, Map.of(), Map.of(), "v"
        );
        MondrianGroupKey key = new MondrianGroupKey(3, false, false);

        // p = 0.5 → both in set
        ConformalResult both = predictor.predict(model, 0.5, key);
        assertTrue(both.predictionSet().containsAll(
                List.of(ConformalResult.Side.TOP, ConformalResult.Side.BOT)));

        // p = 0.85 → only top
        ConformalResult onlyTop = predictor.predict(model, 0.85, key);
        assertTrue(onlyTop.predictionSet().contains(ConformalResult.Side.TOP));
        assertFalse(onlyTop.predictionSet().contains(ConformalResult.Side.BOT));
    }

    @Test
    void predictCarriesCoverageAndIntervalBounds() {
        MondrianSplitConformal model = new MondrianSplitConformal(
                0.1, 0.85, Map.of(), Map.of(), "v"
        );
        MondrianGroupKey key = new MondrianGroupKey(5, true, true);
        ConformalResult result = predictor.predict(model, 0.7, key);
        assertEquals(0.9, result.coverage(), 1e-12);
        assertEquals(0.1, result.alpha(), 1e-12);
        assertEquals(0.85, result.quantile(), 1e-12);
        // Interval clamped to [0, 1]
        assertEquals(0.15, result.intervalLow(), 1e-12);
        assertEquals(0.85, result.intervalHigh(), 1e-12);
        assertEquals(MondrianSplitConformal.METHOD, result.method());
        assertEquals("5|true|true", result.groupKey());
    }

    @Test
    void predictUsesGroupQuantileWhenAvailable() {
        MondrianSplitConformal model = new MondrianSplitConformal(
                0.1, 0.9,
                Map.of("3|false|true", 0.7),
                Map.of("3|false|true", 100),
                "v"
        );
        MondrianGroupKey known = new MondrianGroupKey(3, false, true);
        MondrianGroupKey unknown = new MondrianGroupKey(5, true, false);
        assertEquals(0.7, predictor.predict(model, 0.5, known).quantile(), 1e-12);
        assertEquals(0.9, predictor.predict(model, 0.5, unknown).quantile(), 1e-12);
    }

    @Test
    void predictNullGroupKeyUsesFallback() {
        MondrianSplitConformal model = new MondrianSplitConformal(
                0.1, 0.88, Map.of(), Map.of(), "v"
        );
        ConformalResult result = predictor.predict(model, 0.5, null);
        assertEquals(0.88, result.quantile(), 1e-12);
        assertEquals("", result.groupKey());
    }

    @Test
    void predictRejectsNullModelAndOutOfRangePTop() {
        MondrianSplitConformal model = new MondrianSplitConformal(0.1, 0.9, Map.of(), Map.of(), "v");
        MondrianGroupKey key = new MondrianGroupKey(3, false, false);
        assertThrows(IllegalArgumentException.class,
                () -> predictor.predict(null, 0.5, key));
        assertThrows(IllegalArgumentException.class,
                () -> predictor.predict(model, -0.1, key));
        assertThrows(IllegalArgumentException.class,
                () -> predictor.predict(model, 1.5, key));
    }

    // ---- Empirical coverage ---------------------------------------------

    @Test
    void empiricalCoverageOnSelfFitCalibrationIsApproximatelyOneMinusAlpha() {
        // Generate ~well-calibrated samples then check that the prediction set
        // covers the true label at roughly 1 - alpha.
        Random rng = new Random(42);
        List<ConformalSample> samples = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            double pTop = rng.nextDouble();
            boolean topWon = rng.nextDouble() < pTop;
            samples.add(new ConformalSample(pTop, topWon, null));
        }
        MondrianSplitConformal model = predictor.fit(samples, 0.1, 30, "v");

        int covered = 0;
        for (ConformalSample sample : samples) {
            ConformalResult result = predictor.predict(model, sample.pTop(), null);
            ConformalResult.Side trueSide = sample.topWon()
                    ? ConformalResult.Side.TOP : ConformalResult.Side.BOT;
            if (result.predictionSet().contains(trueSide)) {
                covered++;
            }
        }
        double rate = covered / 1000.0;
        // 1 - alpha = 0.9; allow ±5pp for sample noise.
        assertTrue(rate >= 0.85 && rate <= 0.95,
                "empirical coverage rate=" + rate + " should be near 0.9");
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}
