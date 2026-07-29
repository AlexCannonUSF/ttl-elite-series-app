package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeBet;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveProfileBuilderTests {

    private static final double EPS = 1e-9;
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-05-19T12:00:00");

    /** Production defaults so the assertions exercise real clamps. */
    private static final AdaptiveProfileBuilder.AdaptiveConfig DEFAULT_CONFIG =
            new AdaptiveProfileBuilder.AdaptiveConfig(
                    8,     // minSettledDecisions
                    3,     // triggerMinDecisions
                    14.0,  // learningHalfLifeDays
                    0.02,  // maxEdgeShift
                    1.5,   // maxSelectionScoreShift
                    0.20,  // maxStakeMultiplierDelta
                    0.04   // minEdgeForBet
            );

    @Test
    void belowMinDecisions_returnsThinProfileWithMinEdge() {
        // 4 samples < minSettledDecisions=8 → thin profile, but minEdgeForBet is preserved.
        List<AdaptiveDecisionSample> samples = List.of(
                sample("smash", PaperTradeBet.STATUS_WON, 0.55, 0.10, 10.0, 5.0, NOW.minusDays(1)),
                sample("smash", PaperTradeBet.STATUS_LOST, 0.50, 0.08, 10.0, -10.0, NOW.minusDays(2)),
                sample("topspin", PaperTradeBet.STATUS_WON, 0.60, 0.12, 10.0, 6.0, NOW.minusDays(3)),
                sample("topspin", PaperTradeBet.STATUS_LOST, 0.45, 0.05, 10.0, -10.0, NOW.minusDays(4))
        );

        AdaptiveProfile profile = AdaptiveProfileBuilder.buildAdaptiveProfile(samples, DEFAULT_CONFIG, NOW);

        assertEquals(4, profile.sampleSize());
        assertEquals(0.0, profile.reliability(), EPS);
        assertEquals(0.0, profile.edgeShift(), EPS);
        assertEquals(1.0, profile.stakeMultiplier(), EPS, "no adaptation → stake stays at 1.0");
        assertEquals(0.04, profile.avgSettledEdge(), EPS, "minEdgeForBet preserved in thin profile");
        assertTrue(profile.triggerSignals().isEmpty());
    }

    @Test
    void nullOrEmpty_samples_areTreatedAsBelowMin() {
        AdaptiveProfile profileNull = AdaptiveProfileBuilder.buildAdaptiveProfile(null, DEFAULT_CONFIG, NOW);
        AdaptiveProfile profileEmpty = AdaptiveProfileBuilder.buildAdaptiveProfile(List.of(), DEFAULT_CONFIG, NOW);

        // Both fall into the "below min decisions" branch (0 < 8).
        assertEquals(0, profileNull.sampleSize());
        assertEquals(0, profileEmpty.sampleSize());
        assertEquals(1.0, profileNull.stakeMultiplier(), EPS);
        assertEquals(1.0, profileEmpty.stakeMultiplier(), EPS);
    }

    @Test
    void calibratedCohort_yieldsZeroEdgeShift_andReliabilityGrowing() {
        // 16 evenly-split WON/LOST rows around model prob 0.50 → calibration error ≈ 0.
        // ROI signal ≈ 0 because pnl sums to zero. Therefore edge shift hovers near 0
        // and reliability climbs because weightSum exceeds the support target.
        List<AdaptiveDecisionSample> samples = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            String status = i % 2 == 0 ? PaperTradeBet.STATUS_WON : PaperTradeBet.STATUS_LOST;
            double pnl = i % 2 == 0 ? 10.0 : -10.0;
            samples.add(sample("smash", status, 0.50, 0.10, 10.0, pnl, NOW.minusDays(1)));
        }

        AdaptiveProfile profile = AdaptiveProfileBuilder.buildAdaptiveProfile(samples, DEFAULT_CONFIG, NOW);

        assertEquals(16, profile.sampleSize());
        // Calibration error ≈ 0 and roi ≈ 0 → edgeShift very close to 0.
        assertEquals(0.0, profile.edgeShift(), 1e-3);
        assertTrue(profile.reliability() > 0.2, "reliability climbs once support is healthy");
        // stakeMultiplier should also stay near 1.0 because the normalised shift is small.
        assertEquals(1.0, profile.stakeMultiplier(), 1e-2);
    }

    @Test
    void underWaterCohort_pushesEdgeShiftPositive_andDampensStake() {
        // Model said 0.65, but reality is only 50% wins → calibration error positive
        // AND pnl negative → roi negative → edgeShiftRaw = +calibration_err*0.06 + (-roi)*0.04
        // → positive raw shift → positive clamped edgeShift, stakeMultiplier < 1.
        List<AdaptiveDecisionSample> samples = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            String status = i % 2 == 0 ? PaperTradeBet.STATUS_WON : PaperTradeBet.STATUS_LOST;
            double pnl = i % 2 == 0 ? 8.0 : -10.0; // net negative
            samples.add(sample("smash", status, 0.65, 0.15, 10.0, pnl, NOW.minusDays(1)));
        }

        AdaptiveProfile profile = AdaptiveProfileBuilder.buildAdaptiveProfile(samples, DEFAULT_CONFIG, NOW);

        assertTrue(profile.edgeShift() > 0.0, "model overconfident + negative roi → positive edge nudge");
        assertTrue(profile.stakeMultiplier() < 1.0, "stake gets dampened");
        assertTrue(profile.stakeMultiplier() >= 0.80, "but not below 1 - maxStakeMultiplierDelta=0.20");
        assertTrue(profile.selectionPenalty() >= 0.0, "selection penalty kicks in for positive edge shifts");
    }

    @Test
    void triggerSignal_populatedWhenBelowTriggerMinIsExceeded() {
        // Provide 9 rows on "smash" so it exceeds triggerMinDecisions=3,
        // and 2 rows on "topspin" so it does NOT.
        List<AdaptiveDecisionSample> samples = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            String status = i < 3 ? PaperTradeBet.STATUS_LOST : PaperTradeBet.STATUS_WON;
            double pnl = i < 3 ? -10.0 : 10.0;
            samples.add(sample("smash", status, 0.60, 0.10, 10.0, pnl, NOW.minusDays(1)));
        }
        samples.add(sample("topspin", PaperTradeBet.STATUS_WON, 0.55, 0.10, 10.0, 5.0, NOW.minusDays(1)));
        samples.add(sample("topspin", PaperTradeBet.STATUS_LOST, 0.50, 0.08, 10.0, -10.0, NOW.minusDays(2)));

        AdaptiveProfile profile = AdaptiveProfileBuilder.buildAdaptiveProfile(samples, DEFAULT_CONFIG, NOW);

        assertTrue(profile.triggerSignals().containsKey("smash"));
        // topspin only 2 rows, below triggerMinDecisions=3 → no signal.
        assertTrue(!profile.triggerSignals().containsKey("topspin"));
        TriggerAdaptiveSignal smashSig = profile.triggerSignals().get("smash");
        assertEquals(9, smashSig.sampleSize());
    }

    @Test
    void recencyWeight_decaysByCalendarDays_andDefaultsToOneOnNulls() {
        // 14-day half-life → 14 days back yields 0.5
        double w14 = AdaptiveProfileBuilder.recencyWeight(NOW.minusDays(14), NOW, 14.0);
        assertEquals(0.5, w14, 1e-9);
        // 28-day back → 0.25
        double w28 = AdaptiveProfileBuilder.recencyWeight(NOW.minusDays(28), NOW, 14.0);
        assertEquals(0.25, w28, 1e-9);
        // Same-day → 1.0
        assertEquals(1.0, AdaptiveProfileBuilder.recencyWeight(NOW, NOW, 14.0), EPS);
        // Nulls → 1.0
        assertEquals(1.0, AdaptiveProfileBuilder.recencyWeight(null, NOW, 14.0), EPS);
        assertEquals(1.0, AdaptiveProfileBuilder.recencyWeight(NOW, null, 14.0), EPS);
    }

    private static AdaptiveDecisionSample sample(String trigger,
                                                 String status,
                                                 double modelProb,
                                                 double edge,
                                                 double stake,
                                                 double pnl,
                                                 LocalDateTime settledAt) {
        return new AdaptiveDecisionSample(
                null,         // betId — irrelevant for the math
                trigger,
                status,
                modelProb,
                modelProb - edge,  // implied roughly modelProb − edge
                edge,
                stake,
                pnl,
                0.20,         // confidenceWidth — not exercised here
                settledAt
        );
    }
}
