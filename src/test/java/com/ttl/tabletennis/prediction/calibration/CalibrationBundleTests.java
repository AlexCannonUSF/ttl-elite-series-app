package com.ttl.tabletennis.prediction.calibration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalibrationBundleTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void calibrateAppliesPlattThenIsotonic() {
        PlattCalibrator platt = new PlattCalibrator(1.0, 0.0, "v");
        IsotonicCalibrator iso = new IsotonicCalibrator(
                new double[]{0.0, 0.5, 1.0},
                new double[]{0.0, 0.6, 1.0},
                "v"
        );
        MondrianSplitConformal conformal = new MondrianSplitConformal(
                0.1, 0.9, Map.of(), Map.of(), "v"
        );
        CalibrationBundle bundle = new CalibrationBundle(platt, iso, conformal);
        double calibrated = bundle.calibrate(0.5);
        assertEquals(0.6, calibrated, 1e-6);
    }

    @Test
    void loadFromDirectoryReadsThreeJsonArtefacts() throws Exception {
        Files.writeString(tempDir.resolve("platt.json"),
                "{\"type\":\"platt\",\"version\":\"v3.0.0\",\"coef\":1.0,\"intercept\":0.0}");
        Files.writeString(tempDir.resolve("isotonic.json"),
                "{\"type\":\"isotonic\",\"version\":\"v3.0.0\",\"x\":[0.0,1.0],\"y\":[0.0,1.0]}");
        Files.writeString(tempDir.resolve("conformal.json"),
                "{\"type\":\"mondrian-split-conformal\",\"version\":\"v3.0.0\",\"alpha\":0.1,\"fallback_quantile\":0.9,\"groups\":{}}");

        CalibrationBundle bundle = CalibrationBundle.loadFromDirectory(tempDir, objectMapper);

        assertEquals(0.5, bundle.calibrate(0.5), 1e-6);
        var u = bundle.uncertainty(0.95, new MondrianGroupKey(3, false, false));
        assertEquals(MondrianSplitConformal.Label.CONFIDENT_TOP, u.label());
        assertEquals(0.9, u.coverage());
    }

    @Test
    void loadFromDirectoryRejectsNonDirectory() throws Exception {
        Path file = tempDir.resolve("nope.txt");
        Files.writeString(file, "x");
        assertThrows(IllegalArgumentException.class,
                () -> CalibrationBundle.loadFromDirectory(file, objectMapper));
    }

    @Test
    void constructorRejectsNullDependencies() {
        PlattCalibrator platt = new PlattCalibrator(1.0, 0.0, "v");
        IsotonicCalibrator iso = new IsotonicCalibrator(new double[0], new double[0], "v");
        MondrianSplitConformal conformal = new MondrianSplitConformal(0.1, 0.9, Map.of(), Map.of(), "v");

        assertThrows(IllegalArgumentException.class, () -> new CalibrationBundle(null, iso, conformal));
        assertThrows(IllegalArgumentException.class, () -> new CalibrationBundle(platt, null, conformal));
        assertThrows(IllegalArgumentException.class, () -> new CalibrationBundle(platt, iso, null));
    }

    @Test
    void calibrateAllPreservesLength() {
        CalibrationBundle bundle = new CalibrationBundle(
                new PlattCalibrator(1.0, 0.0, "v"),
                new IsotonicCalibrator(new double[]{0.0, 1.0}, new double[]{0.0, 1.0}, "v"),
                new MondrianSplitConformal(0.1, 0.9, Map.of(), Map.of(), "v")
        );
        double[] out = bundle.calibrateAll(new double[]{0.1, 0.5, 0.9});
        assertEquals(3, out.length);
        assertTrue(out[0] < out[1] && out[1] < out[2]);
    }
}
