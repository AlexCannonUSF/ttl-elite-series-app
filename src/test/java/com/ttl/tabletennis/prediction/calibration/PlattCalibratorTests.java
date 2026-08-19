package com.ttl.tabletennis.prediction.calibration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlattCalibratorTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void identityCalibratorIsApproximatelyMonotone() {
        PlattCalibrator id = new PlattCalibrator(1.0, 0.0, "v3.0.0");
        assertEquals(0.5, id.apply(0.5), 1e-6);
        assertTrue(id.apply(0.99) > id.apply(0.01));
        assertTrue(id.apply(0.99) > id.apply(0.5));
        assertTrue(id.apply(0.5) > id.apply(0.01));
    }

    @Test
    void shiftedInterceptShiftsProbabilitiesUp() {
        PlattCalibrator shifted = new PlattCalibrator(1.0, 1.0, "v");
        assertTrue(shifted.apply(0.5) > 0.5);
    }

    @Test
    void clipsExtremeInputs() {
        PlattCalibrator id = new PlattCalibrator(1.0, 0.0, "v");
        double atZero = id.apply(0.0);
        double atOne = id.apply(1.0);
        assertTrue(atZero > 0.0 && atZero < 0.01, "near-zero clipped to >0");
        assertTrue(atOne < 1.0 && atOne > 0.99, "near-one clipped to <1");
    }

    @Test
    void fromJsonRoundTripPreservesParameters() throws Exception {
        String json = "{\"type\":\"platt\",\"version\":\"v3.0.0\",\"coef\":1.5,\"intercept\":-0.25}";
        PlattCalibrator p = PlattCalibrator.fromJson(objectMapper.readTree(json));
        assertEquals(1.5, p.coef());
        assertEquals(-0.25, p.intercept());
        assertEquals("v3.0.0", p.version());
    }

    @Test
    void rejectsWrongType() throws Exception {
        var bad = objectMapper.readTree("{\"type\":\"isotonic\"}");
        assertThrows(IllegalArgumentException.class, () -> PlattCalibrator.fromJson(bad));
    }

    @Test
    void rejectsNonFiniteParameters() {
        assertThrows(IllegalArgumentException.class, () -> new PlattCalibrator(Double.NaN, 0.0, "v"));
        assertThrows(IllegalArgumentException.class, () -> new PlattCalibrator(0.0, Double.POSITIVE_INFINITY, "v"));
    }

    @Test
    void applyAllPreservesLengthAndOrder() {
        PlattCalibrator p = new PlattCalibrator(2.0, 0.0, "v");
        double[] out = p.applyAll(new double[]{0.1, 0.5, 0.9});
        assertEquals(3, out.length);
        assertTrue(out[0] < out[1] && out[1] < out[2]);
    }
}
