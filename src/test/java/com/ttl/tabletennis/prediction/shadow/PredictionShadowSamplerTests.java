package com.ttl.tabletennis.prediction.shadow;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PredictionShadowSamplerTests {

    @Test
    void zeroRateAlwaysSkips() {
        PredictionShadowSampler s = new PredictionShadowSampler(0.0);
        assertFalse(s.shouldShadow(1, 2, LocalDate.of(2026, 5, 18)));
    }

    @Test
    void oneRateAlwaysShadows() {
        PredictionShadowSampler s = new PredictionShadowSampler(1.0);
        assertTrue(s.shouldShadow(1, 2, LocalDate.of(2026, 5, 18)));
    }

    @Test
    void rateBetweenZeroAndOneIsStableAcrossCalls() {
        PredictionShadowSampler s = new PredictionShadowSampler(0.5);
        boolean first = s.shouldShadow(101L, 202L, LocalDate.of(2026, 5, 18));
        boolean second = s.shouldShadow(101L, 202L, LocalDate.of(2026, 5, 18));
        assertEquals(first, second);
    }

    @Test
    void playerOrderDoesNotChangeBucket() {
        PredictionShadowSampler s = new PredictionShadowSampler(0.5);
        LocalDate date = LocalDate.of(2026, 5, 18);
        assertEquals(s.shouldShadow(7L, 42L, date), s.shouldShadow(42L, 7L, date));
    }

    @Test
    void rateApproximatesRequestedFraction() {
        PredictionShadowSampler s = new PredictionShadowSampler(0.05);
        int hits = 0;
        int total = 10_000;
        for (int i = 0; i < total; i++) {
            if (s.shouldShadow(i, i + 1, LocalDate.of(2026, 5, 18))) {
                hits++;
            }
        }
        double observed = (double) hits / total;
        // FNV-1a hashes spread evenly enough that 10k samples should land within ±1.5%.
        assertTrue(observed >= 0.035 && observed <= 0.065,
                "expected ~5% sample rate, observed=" + observed);
    }

    @Test
    void constructorRejectsOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> new PredictionShadowSampler(-0.01));
        assertThrows(IllegalArgumentException.class, () -> new PredictionShadowSampler(1.01));
    }

    @Test
    void asOfDateIsPartOfTheStableKey() {
        long monday = PredictionShadowSampler.stableKey(11L, 22L, LocalDate.of(2026, 5, 18));
        long tuesday = PredictionShadowSampler.stableKey(11L, 22L, LocalDate.of(2026, 5, 19));
        assertTrue(monday != tuesday, "different dates should yield different stable keys");
    }
}
