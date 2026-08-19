package com.ttl.tabletennis.prediction.calibration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mondrian split-conformal predictor per Prediction Engine Spec §8.
 *
 * <p>For a binary problem, given calibrated {@code p_top}, the prediction
 * set membership is:
 * <ul>
 *   <li>{@code top ∈ set} iff {@code p_top ≥ 1 - q̂}</li>
 *   <li>{@code bot ∈ set} iff {@code p_top ≤ q̂}</li>
 * </ul>
 * The labels resolve as {@code CONFIDENT_TOP}, {@code CONFIDENT_BOT},
 * {@code AMBIGUOUS}, or {@code ANOMALOUS} (§8.2). The interval is reported
 * as {@code [1 - q̂, q̂]} around the calibrated probability so callers can
 * surface uncertainty without re-computing the quantile.
 */
public final class MondrianSplitConformal {

    public static final String METHOD = "mondrian-split-conformal";

    private final double alpha;
    private final double fallbackQuantile;
    private final Map<String, Double> quantiles;
    private final Map<String, Integer> counts;
    private final String version;

    public MondrianSplitConformal(double alpha,
                                  double fallbackQuantile,
                                  Map<String, Double> quantiles,
                                  Map<String, Integer> counts,
                                  String version) {
        if (alpha <= 0.0 || alpha >= 1.0) {
            throw new IllegalArgumentException("alpha must lie in (0, 1)");
        }
        if (fallbackQuantile < 0.0 || fallbackQuantile > 1.0) {
            throw new IllegalArgumentException("fallbackQuantile must lie in [0, 1]");
        }
        this.alpha = alpha;
        this.fallbackQuantile = fallbackQuantile;
        this.quantiles = quantiles == null ? Map.of() : Map.copyOf(quantiles);
        this.counts = counts == null ? Map.of() : Map.copyOf(counts);
        this.version = version == null || version.isBlank() ? "v3.0.0" : version.trim();
    }

    public double quantileFor(String groupKey) {
        if (groupKey == null) {
            return fallbackQuantile;
        }
        return quantiles.getOrDefault(groupKey, fallbackQuantile);
    }

    public double quantileFor(MondrianGroupKey groupKey) {
        return groupKey == null ? fallbackQuantile : quantileFor(groupKey.encode());
    }

    public Uncertainty uncertainty(double calibratedPTop, MondrianGroupKey groupKey) {
        double q = quantileFor(groupKey);
        double intervalLow = clamp(1.0 - q);
        double intervalHigh = clamp(q);
        Label label = classify(calibratedPTop, q);
        return new Uncertainty(
                1.0 - alpha,
                alpha,
                label,
                intervalLow,
                intervalHigh,
                groupKey == null ? "" : groupKey.encode(),
                q,
                METHOD,
                version
        );
    }

    static Label classify(double p, double q) {
        boolean topIn = p >= 1.0 - q;
        boolean botIn = p <= q;
        if (topIn && botIn) {
            return Label.AMBIGUOUS;
        }
        if (topIn) {
            return Label.CONFIDENT_TOP;
        }
        if (botIn) {
            return Label.CONFIDENT_BOT;
        }
        return Label.ANOMALOUS;
    }

    public double alpha() {
        return alpha;
    }

    public double fallbackQuantile() {
        return fallbackQuantile;
    }

    public Map<String, Double> quantiles() {
        return quantiles;
    }

    public Map<String, Integer> counts() {
        return counts;
    }

    public String version() {
        return version;
    }

    public static MondrianSplitConformal fromJson(JsonNode root) {
        String type = root.path("type").asText("");
        if (!METHOD.equals(type)) {
            throw new IllegalArgumentException("expected type=" + METHOD + ", got " + type);
        }
        double alpha = root.path("alpha").asDouble(0.1);
        double fallback = root.path("fallback_quantile").asDouble(0.9);
        JsonNode groups = root.path("groups");
        Map<String, Double> quantiles = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (groups.isObject()) {
            var fields = groups.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                JsonNode body = entry.getValue();
                quantiles.put(entry.getKey(), body.path("quantile").asDouble(fallback));
                counts.put(entry.getKey(), body.path("n").asInt(0));
            }
        }
        return new MondrianSplitConformal(
                alpha,
                fallback,
                quantiles,
                counts,
                root.path("version").asText("v3.0.0")
        );
    }

    public static MondrianSplitConformal load(Path path, ObjectMapper objectMapper) throws IOException {
        try (var reader = Files.newBufferedReader(path)) {
            return fromJson(objectMapper.readTree(reader));
        }
    }

    private static double clamp(double v) {
        if (v < 0.0) {
            return 0.0;
        }
        if (v > 1.0) {
            return 1.0;
        }
        return v;
    }

    public enum Label { CONFIDENT_TOP, CONFIDENT_BOT, AMBIGUOUS, ANOMALOUS }

    public record Uncertainty(double coverage,
                              double alpha,
                              Label label,
                              double intervalLow,
                              double intervalHigh,
                              String groupKey,
                              double quantile,
                              String method,
                              String version) { }
}
