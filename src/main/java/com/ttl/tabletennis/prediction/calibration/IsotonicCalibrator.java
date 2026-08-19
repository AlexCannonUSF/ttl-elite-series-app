package com.ttl.tabletennis.prediction.calibration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stage 2 isotonic regression per Prediction Engine Spec §7.3.
 * Stores breakpoints as monotone-non-decreasing arrays; scoring is a binary
 * search + linear interpolation between adjacent breakpoints.
 */
public record IsotonicCalibrator(double[] xBreakpoints, double[] yBreakpoints, String version) {

    public IsotonicCalibrator {
        if (xBreakpoints == null || yBreakpoints == null) {
            throw new IllegalArgumentException("breakpoints must not be null");
        }
        if (xBreakpoints.length != yBreakpoints.length) {
            throw new IllegalArgumentException("breakpoint arrays must align");
        }
        for (int i = 1; i < xBreakpoints.length; i++) {
            if (xBreakpoints[i] < xBreakpoints[i - 1]) {
                throw new IllegalArgumentException("x breakpoints must be non-decreasing");
            }
        }
        version = version == null || version.isBlank() ? "v3.0.0" : version.trim();
    }

    public double apply(double prob) {
        if (xBreakpoints.length == 0) {
            return clamp(prob);
        }
        double p = clamp(prob);
        if (p <= xBreakpoints[0]) {
            return yBreakpoints[0];
        }
        if (p >= xBreakpoints[xBreakpoints.length - 1]) {
            return yBreakpoints[yBreakpoints.length - 1];
        }
        int lo = 0;
        int hi = xBreakpoints.length - 1;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (xBreakpoints[mid] <= p) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double x0 = xBreakpoints[lo];
        double x1 = xBreakpoints[hi];
        double y0 = yBreakpoints[lo];
        double y1 = yBreakpoints[hi];
        if (x1 == x0) {
            return y0;
        }
        return y0 + (y1 - y0) * (p - x0) / (x1 - x0);
    }

    public double[] applyAll(double[] probs) {
        if (probs == null) {
            return new double[0];
        }
        double[] out = new double[probs.length];
        for (int i = 0; i < probs.length; i++) {
            out[i] = apply(probs[i]);
        }
        return out;
    }

    public static IsotonicCalibrator fromJson(JsonNode root) {
        String type = root.path("type").asText("");
        if (!"isotonic".equals(type)) {
            throw new IllegalArgumentException("expected type=isotonic, got " + type);
        }
        JsonNode xs = root.path("x");
        JsonNode ys = root.path("y");
        if (!xs.isArray() || !ys.isArray()) {
            throw new IllegalArgumentException("isotonic JSON must have x and y arrays");
        }
        if (xs.size() != ys.size()) {
            throw new IllegalArgumentException("x and y arrays must align");
        }
        double[] x = new double[xs.size()];
        double[] y = new double[ys.size()];
        for (int i = 0; i < x.length; i++) {
            x[i] = xs.get(i).asDouble();
            y[i] = ys.get(i).asDouble();
        }
        return new IsotonicCalibrator(x, y, root.path("version").asText("v3.0.0"));
    }

    public static IsotonicCalibrator load(Path path, ObjectMapper objectMapper) throws IOException {
        try (var reader = Files.newBufferedReader(path)) {
            return fromJson(objectMapper.readTree(reader));
        }
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
