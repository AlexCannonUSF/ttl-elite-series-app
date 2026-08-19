package com.ttl.tabletennis.prediction.calibration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MondrianSplitConformalTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mondrianGroupKeyEncodeDecodeRoundTrip() {
        MondrianGroupKey key = new MondrianGroupKey(5, true, false);
        assertEquals("5|true|false", key.encode());
        assertEquals(key, MondrianGroupKey.decode(key.encode()));
    }

    @Test
    void quantileForUnknownGroupReturnsFallback() {
        MondrianSplitConformal conformal = new MondrianSplitConformal(
                0.1, 0.91,
                Map.of("3|false|true", 0.88),
                Map.of("3|false|true", 200),
                "v"
        );
        assertEquals(0.88, conformal.quantileFor("3|false|true"));
        assertEquals(0.91, conformal.quantileFor("unknown"));
        assertEquals(0.91, conformal.quantileFor((String) null));
    }

    @Test
    void uncertaintyLabelsBinarySetMembership() {
        MondrianSplitConformal conformal = new MondrianSplitConformal(
                0.1, 0.6, Map.of(), Map.of(), "v"
        );
        MondrianGroupKey key = new MondrianGroupKey(3, false, false);
        // With q̂=0.6, top in set iff p_top ≥ 0.4; bot in set iff p_top ≤ 0.6.
        assertEquals(MondrianSplitConformal.Label.CONFIDENT_TOP, conformal.uncertainty(0.95, key).label());
        assertEquals(MondrianSplitConformal.Label.CONFIDENT_BOT, conformal.uncertainty(0.05, key).label());
        assertEquals(MondrianSplitConformal.Label.AMBIGUOUS, conformal.uncertainty(0.5, key).label());
    }

    @Test
    void uncertaintyAnomalousWhenQuantileBelowHalf() {
        MondrianSplitConformal conformal = new MondrianSplitConformal(
                0.1, 0.3, Map.of(), Map.of(), "v"
        );
        // q̂=0.3: top in set iff p≥0.7, bot in set iff p≤0.3. p=0.5 → neither.
        var u = conformal.uncertainty(0.5, new MondrianGroupKey(3, false, false));
        assertEquals(MondrianSplitConformal.Label.ANOMALOUS, u.label());
    }

    @Test
    void uncertaintyCoverageMatchesOneMinusAlpha() {
        MondrianSplitConformal conformal = new MondrianSplitConformal(
                0.1, 0.9, Map.of(), Map.of(), "v"
        );
        var u = conformal.uncertainty(0.7, new MondrianGroupKey(5, true, true));
        assertEquals(0.9, u.coverage());
        assertEquals(0.1, u.alpha());
        assertEquals(MondrianSplitConformal.METHOD, u.method());
        assertEquals("5|true|true", u.groupKey());
        assertEquals(0.9, u.quantile());
        // Interval bounds clamped to [0,1]
        assertTrue(u.intervalLow() >= 0.0 && u.intervalHigh() <= 1.0);
    }

    @Test
    void fromJsonReadsGroupsAndCounts() throws Exception {
        String json = """
                {
                  "type": "mondrian-split-conformal",
                  "version": "v3.0.0",
                  "alpha": 0.1,
                  "fallback_quantile": 0.92,
                  "groups": {
                    "3|false|true": {"quantile": 0.88, "n": 240},
                    "5|true|true": {"quantile": 0.95, "n": 60}
                  }
                }
                """;
        MondrianSplitConformal conformal = MondrianSplitConformal.fromJson(objectMapper.readTree(json));
        assertEquals(0.1, conformal.alpha());
        assertEquals(0.92, conformal.fallbackQuantile());
        assertEquals(0.88, conformal.quantileFor("3|false|true"));
        assertEquals(240, conformal.counts().get("3|false|true"));
    }

    @Test
    void constructorRejectsInvalidAlpha() {
        assertThrows(IllegalArgumentException.class,
                () -> new MondrianSplitConformal(0.0, 0.9, Map.of(), Map.of(), "v"));
        assertThrows(IllegalArgumentException.class,
                () -> new MondrianSplitConformal(1.0, 0.9, Map.of(), Map.of(), "v"));
    }

    @Test
    void constructorRejectsFallbackOutOfRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new MondrianSplitConformal(0.1, -0.1, Map.of(), Map.of(), "v"));
        assertThrows(IllegalArgumentException.class,
                () -> new MondrianSplitConformal(0.1, 1.1, Map.of(), Map.of(), "v"));
    }
}
