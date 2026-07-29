package com.ttl.tabletennis.service.papertrade;

import org.junit.jupiter.api.Test;

import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.EPS;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.clamp;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.normalizeTrigger;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.round2;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.round4;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.safeText;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.valueOrZero;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PaperTradingHelpersTests {

    @Test
    void clampInt() {
        assertEquals(5, clamp(3, 5, 10));
        assertEquals(10, clamp(15, 5, 10));
        assertEquals(7, clamp(7, 5, 10));
    }

    @Test
    void clampDouble() {
        assertEquals(0.5, clamp(0.3, 0.5, 1.0), 1e-9);
        assertEquals(1.0, clamp(1.5, 0.5, 1.0), 1e-9);
        assertEquals(0.7, clamp(0.7, 0.5, 1.0), 1e-9);
    }

    @Test
    void round2RoundsToTwoDecimals() {
        assertEquals(1.23, round2(1.234), 1e-9);
        assertEquals(1.24, round2(1.235), 1e-9);
    }

    @Test
    void round4RoundsToFourDecimals() {
        assertEquals(1.2346, round4(1.23456789), 1e-9);
    }

    @Test
    void valueOrZeroHandlesNullable() {
        assertEquals(0.0, valueOrZero(null), 1e-9);
        assertEquals(3.5, valueOrZero(3.5), 1e-9);
    }

    @Test
    void safeTextTrimsAndFallsBack() {
        assertEquals("foo", safeText(" foo ", "fallback"));
        assertEquals("fallback", safeText("", "fallback"));
        assertEquals("fallback", safeText(null, "fallback"));
        assertEquals("fallback", safeText("   ", "fallback"));
    }

    @Test
    void normalizeTriggerCanonicalises() {
        assertEquals("unknown trigger", normalizeTrigger(null));
        assertEquals("unknown trigger", normalizeTrigger(""));
        assertEquals("recent form delta", normalizeTrigger("  Recent Form Delta  "));
    }

    @Test
    void epsIsPositiveAndSmall() {
        // belt-and-braces — EPS is a contract for downstream services
        assertEquals(1e-9, EPS, 0);
    }
}
