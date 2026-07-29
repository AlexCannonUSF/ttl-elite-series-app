package com.ttl.tabletennis.prediction.calibration;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Convenience bundle that loads the three calibrator artefacts shipped
 * alongside a blender model and applies them in spec order.
 *
 * <p>Layout matches the Python writer:
 * <pre>
 * models/prediction/variant-a-v3.0.0/
 *   ├── blender.lgb.model
 *   ├── feature_registry.json
 *   ├── platt.json
 *   ├── isotonic.json
 *   └── conformal.json
 * </pre>
 */
public record CalibrationBundle(PlattCalibrator platt,
                                IsotonicCalibrator isotonic,
                                MondrianSplitConformal conformal) {

    public CalibrationBundle {
        if (platt == null) {
            throw new IllegalArgumentException("platt must not be null");
        }
        if (isotonic == null) {
            throw new IllegalArgumentException("isotonic must not be null");
        }
        if (conformal == null) {
            throw new IllegalArgumentException("conformal must not be null");
        }
    }

    public double calibrate(double rawProbability) {
        return isotonic.apply(platt.apply(rawProbability));
    }

    public double[] calibrateAll(double[] rawProbabilities) {
        return isotonic.applyAll(platt.applyAll(rawProbabilities));
    }

    public MondrianSplitConformal.Uncertainty uncertainty(double calibratedPTop, MondrianGroupKey key) {
        return conformal.uncertainty(calibratedPTop, key);
    }

    public static CalibrationBundle loadFromDirectory(Path directory, ObjectMapper objectMapper) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("not a directory: " + directory);
        }
        return new CalibrationBundle(
                PlattCalibrator.load(directory.resolve("platt.json"), objectMapper),
                IsotonicCalibrator.load(directory.resolve("isotonic.json"), objectMapper),
                MondrianSplitConformal.load(directory.resolve("conformal.json"), objectMapper)
        );
    }
}
