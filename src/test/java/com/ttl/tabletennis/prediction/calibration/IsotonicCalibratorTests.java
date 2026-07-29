package com.ttl.tabletennis.prediction.calibration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IsotonicCalibratorTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void emptyBreakpointsClampsInputToZeroOne() {
        IsotonicCalibrator empty = new IsotonicCalibrator(new double[0], new double[0], "v");
        assertEquals(0.0, empty.apply(-0.1));
        assertEquals(1.0, empty.apply(1.5));
        assertEquals(0.4, empty.apply(0.4));
    }

    @Test
    void breakpointsEnforceMonotonicityAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new IsotonicCalibrator(new double[]{0.5, 0.2}, new double[]{0.1, 0.9}, "v"));
    }

    @Test
    void mismatchedArraysRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new IsotonicCalibrator(new double[]{0.0, 1.0}, new double[]{0.0}, "v"));
    }

    @Test
    void linearInterpolationBetweenBreakpoints() {
        IsotonicCalibrator iso = new IsotonicCalibrator(
                new double[]{0.0, 0.5, 1.0},
                new double[]{0.0, 0.4, 1.0},
                "v"
        );
        assertEquals(0.0, iso.apply(0.0));
        assertEquals(0.4, iso.apply(0.5));
        assertEquals(0.2, iso.apply(0.25), 1e-9);
        assertEquals(0.7, iso.apply(0.75), 1e-9);
        assertEquals(1.0, iso.apply(1.0));
    }

    @Test
    void clampsOutOfRangeInputsToEdgeBreakpoint() {
        IsotonicCalibrator iso = new IsotonicCalibrator(
                new double[]{0.1, 0.9},
                new double[]{0.2, 0.8},
                "v"
        );
        assertEquals(0.2, iso.apply(0.0));
        assertEquals(0.8, iso.apply(1.0));
    }

    @Test
    void fromJsonRoundTrip() throws Exception {
        String json = "{\"type\":\"isotonic\",\"version\":\"v3.0.0\","
                + "\"x\":[0.0,0.5,1.0],\"y\":[0.0,0.6,1.0]}";
        IsotonicCalibrator iso = IsotonicCalibrator.fromJson(objectMapper.readTree(json));
        assertEquals(3, iso.xBreakpoints().length);
        assertEquals(0.6, iso.apply(0.5), 1e-9);
    }

    @Test
    void fromJsonRejectsWrongType() throws Exception {
        var bad = objectMapper.readTree("{\"type\":\"platt\",\"x\":[],\"y\":[]}");
        assertThrows(IllegalArgumentException.class, () -> IsotonicCalibrator.fromJson(bad));
    }

    @Test
    void applyAllPreservesLength() {
        IsotonicCalibrator iso = new IsotonicCalibrator(
                new double[]{0.0, 1.0},
                new double[]{0.0, 1.0},
                "v"
        );
        double[] out = iso.applyAll(new double[]{0.0, 0.3, 0.7, 1.0});
        assertEquals(4, out.length);
        assertTrue(out[0] <= out[1] && out[1] <= out[2] && out[2] <= out[3]);
    }
}
