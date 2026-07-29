package com.ttl.tabletennis.prediction.calibration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stage 1 Platt scaling per Prediction Engine Spec §7.2.
 * Mirrors {@code app.training.calibration.PlattCalibrator} on the Python side.
 */
public record PlattCalibrator(double coef, double intercept, String version) {

    static final double EPSILON = 1.0e-6;

    public PlattCalibrator {
        if (Double.isNaN(coef) || Double.isInfinite(coef)) {
            throw new IllegalArgumentException("coef must be finite");
        }
        if (Double.isNaN(intercept) || Double.isInfinite(intercept)) {
            throw new IllegalArgumentException("intercept must be finite");
        }
        version = version == null || version.isBlank() ? "v3.0.0" : version.trim();
    }

    public double apply(double rawProbability) {
        double clipped = clip(rawProbability);
        double logit = Math.log(clipped / (1.0 - clipped));
        double z = coef * logit + intercept;
        return sigmoid(z);
    }

    public double[] applyAll(double[] rawProbabilities) {
        if (rawProbabilities == null) {
            return new double[0];
        }
        double[] out = new double[rawProbabilities.length];
        for (int i = 0; i < rawProbabilities.length; i++) {
            out[i] = apply(rawProbabilities[i]);
        }
        return out;
    }

    public static PlattCalibrator fromJson(JsonNode root) {
        String type = root.path("type").asText("");
        if (!"platt".equals(type)) {
            throw new IllegalArgumentException("expected type=platt, got " + type);
        }
        return new PlattCalibrator(
                root.path("coef").asDouble(),
                root.path("intercept").asDouble(),
                root.path("version").asText("v3.0.0")
        );
    }

    public static PlattCalibrator load(Path path, ObjectMapper objectMapper) throws IOException {
        try (var reader = Files.newBufferedReader(path)) {
            return fromJson(objectMapper.readTree(reader));
        }
    }

    static double sigmoid(double z) {
        if (z >= 0) {
            double e = Math.exp(-z);
            return 1.0 / (1.0 + e);
        }
        double e = Math.exp(z);
        return e / (1.0 + e);
    }

    static double clip(double p) {
        if (p < EPSILON) {
            return EPSILON;
        }
        if (p > 1.0 - EPSILON) {
            return 1.0 - EPSILON;
        }
        return p;
    }
}
