package com.ttl.tabletennis.prediction.shadow;

import java.time.LocalDate;

/**
 * Deterministic shadow-traffic sampler. A stable hash of
 * {@code (player1Id, player2Id, asOfDate)} keeps the same matchup either
 * always sampled or always skipped, which makes the diff log easy to
 * interpret across re-runs.
 *
 * <p>The sample rate is the proportion of matchups that fall under
 * ``sampleRate``; default 0.05 per the Phase 04 deliverable.
 */
public final class PredictionShadowSampler {

    public static final double DEFAULT_SAMPLE_RATE = 0.05;

    private final double sampleRate;

    public PredictionShadowSampler(double sampleRate) {
        if (sampleRate < 0.0 || sampleRate > 1.0) {
            throw new IllegalArgumentException("sampleRate must lie in [0, 1]");
        }
        this.sampleRate = sampleRate;
    }

    public double sampleRate() {
        return sampleRate;
    }

    public boolean shouldShadow(long player1Id, long player2Id, LocalDate asOfDate) {
        if (sampleRate <= 0.0) {
            return false;
        }
        if (sampleRate >= 1.0) {
            return true;
        }
        long key = stableKey(player1Id, player2Id, asOfDate);
        // Map the 64-bit key to [0, 1) by treating the unsigned long as a fraction.
        double fraction = (key & Long.MAX_VALUE) / (double) Long.MAX_VALUE;
        return fraction < sampleRate;
    }

    /**
     * Stable hash that does not depend on JVM identityHashCode. The order
     * of the player ids is canonicalised so {@code (a,b)} and {@code (b,a)}
     * fall in the same shadow bucket.
     */
    public static long stableKey(long player1Id, long player2Id, LocalDate asOfDate) {
        long lo = Math.min(player1Id, player2Id);
        long hi = Math.max(player1Id, player2Id);
        long date = asOfDate == null ? 0L : asOfDate.toEpochDay();
        long h = 0xcbf29ce484222325L; // FNV-1a 64-bit offset basis
        h = mix(h, lo);
        h = mix(h, hi);
        h = mix(h, date);
        return h;
    }

    private static long mix(long state, long value) {
        long h = state ^ value;
        h *= 0x100000001b3L; // FNV-1a 64-bit prime
        return h;
    }
}
