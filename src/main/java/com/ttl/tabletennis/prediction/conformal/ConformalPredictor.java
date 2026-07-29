package com.ttl.tabletennis.prediction.conformal;

import com.ttl.tabletennis.prediction.calibration.MondrianGroupKey;
import com.ttl.tabletennis.prediction.calibration.MondrianSplitConformal;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mondrian split-conformal predictor per Prediction Engine Spec §8.
 *
 * <p>Two surfaces:
 *
 * <ol>
 *   <li>{@link #fit(Collection, double, int, String)} — builds a
 *       {@link MondrianSplitConformal} from a labelled calibration
 *       slice. Mirrors the Python writer in
 *       {@code ttl-predict-py/app/training/calibration.py}.</li>
 *   <li>{@link #predict(MondrianSplitConformal, double, MondrianGroupKey)}
 *       — scores a single calibrated p_top against the loaded model and
 *       returns the explicit prediction set (per §8.2) plus the §8.4
 *       uncertainty envelope.</li>
 * </ol>
 *
 * <p>Stateless and safe to call from request paths.
 */
@Service
public class ConformalPredictor {

    public static final double DEFAULT_ALPHA = 0.1;
    public static final int DEFAULT_MIN_GROUP_SIZE = 30;
    public static final double DEFAULT_FALLBACK_QUANTILE = 0.9;

    public MondrianSplitConformal fit(Collection<ConformalSample> samples) {
        return fit(samples, DEFAULT_ALPHA, DEFAULT_MIN_GROUP_SIZE, "v3.0.0");
    }

    public MondrianSplitConformal fit(Collection<ConformalSample> samples,
                                      double alpha,
                                      int minGroupSize,
                                      String version) {
        if (alpha <= 0.0 || alpha >= 1.0) {
            throw new IllegalArgumentException("alpha must lie in (0, 1); was " + alpha);
        }
        if (minGroupSize < 1) {
            throw new IllegalArgumentException("minGroupSize must be >= 1; was " + minGroupSize);
        }
        if (samples == null) {
            samples = List.of();
        }

        Map<String, List<Double>> grouped = new LinkedHashMap<>();
        List<Double> pooled = new ArrayList<>();
        for (ConformalSample sample : samples) {
            if (sample == null) {
                continue;
            }
            double score = sample.nonConformityScore();
            pooled.add(score);
            String key = sample.groupKey() == null ? "" : sample.groupKey().encode();
            if (!key.isEmpty()) {
                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(score);
            }
        }

        double pooledQuantile = pooled.isEmpty()
                ? DEFAULT_FALLBACK_QUANTILE
                : splitQuantile(pooled, alpha);

        Map<String, Double> quantiles = new LinkedHashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (Map.Entry<String, List<Double>> entry : grouped.entrySet()) {
            counts.put(entry.getKey(), entry.getValue().size());
            if (entry.getValue().size() < minGroupSize) {
                quantiles.put(entry.getKey(), pooledQuantile);
                continue;
            }
            quantiles.put(entry.getKey(), splitQuantile(entry.getValue(), alpha));
        }

        return new MondrianSplitConformal(
                alpha,
                pooledQuantile,
                quantiles,
                counts,
                version == null || version.isBlank() ? "v3.0.0" : version.trim()
        );
    }

    public ConformalResult predict(MondrianSplitConformal model,
                                   double pTop,
                                   MondrianGroupKey key) {
        if (model == null) {
            throw new IllegalArgumentException("model must not be null");
        }
        if (Double.isNaN(pTop) || pTop < 0.0 || pTop > 1.0) {
            throw new IllegalArgumentException("pTop must lie in [0, 1]; was " + pTop);
        }
        double q = model.quantileFor(key);
        boolean topIn = pTop >= 1.0 - q;
        boolean botIn = pTop <= q;
        EnumSet<ConformalResult.Side> set = EnumSet.noneOf(ConformalResult.Side.class);
        if (topIn) {
            set.add(ConformalResult.Side.TOP);
        }
        if (botIn) {
            set.add(ConformalResult.Side.BOT);
        }
        ConformalResult.Label label = classify(topIn, botIn);
        double intervalLow = clamp(1.0 - q);
        double intervalHigh = clamp(q);

        return new ConformalResult(
                pTop,
                label,
                set,
                q,
                model.alpha(),
                1.0 - model.alpha(),
                intervalLow,
                intervalHigh,
                key == null ? "" : key.encode(),
                MondrianSplitConformal.METHOD
        );
    }

    /**
     * Quantile of the calibration scores at level {@code alpha} per Spec
     * §8.2: {@code q̂ = ceil((n+1)(1-α))/n}-th order statistic.
     */
    public double splitQuantile(Collection<Double> scores, double alpha) {
        if (scores == null || scores.isEmpty()) {
            return 1.0;
        }
        double[] arr = scores.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(arr);
        int n = arr.length;
        double target = (n + 1) * (1.0 - alpha);
        int rank = (int) Math.ceil(target - 1e-12);
        if (rank < 1) {
            rank = 1;
        } else if (rank > n) {
            rank = n;
        }
        return arr[rank - 1];
    }

    static ConformalResult.Label classify(boolean topIn, boolean botIn) {
        if (topIn && botIn) {
            return ConformalResult.Label.AMBIGUOUS;
        }
        if (topIn) {
            return ConformalResult.Label.CONFIDENT_TOP;
        }
        if (botIn) {
            return ConformalResult.Label.CONFIDENT_BOT;
        }
        return ConformalResult.Label.ANOMALOUS;
    }

    private static double clamp(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }
}
