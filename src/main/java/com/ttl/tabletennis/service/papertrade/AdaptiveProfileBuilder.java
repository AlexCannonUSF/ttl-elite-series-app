package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeBet;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.EPS;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.clamp;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.normalizeTrigger;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.round4;

/**
 * Pure-function builder for {@link AdaptiveProfile}. Takes pre-loaded
 * decision history + a config bundle and returns the calibration snapshot
 * the placement loop consumes.
 *
 * <p>Tenth §4 slice (slice B of the AdaptiveProfile extract — slice A
 * lifted the data records). The original builder lived inside
 * {@code PaperTradingService.buildAdaptiveProfile} alongside repository
 * loads, drift-bookkeeping, and back-fill side effects. Those side effects
 * stay at the caller (the {@code backfillLearningSamples} and
 * {@code persistLearningSample} helpers have non-adaptive consumers — the
 * sync loop and settlement path). The builder here is pure compute:
 * given the rows + config + clock, it returns the same {@code AdaptiveProfile}
 * shape, verbatim from the original.
 *
 * <p>The {@link AdaptiveConfig} record bundles nine {@code @Value}-injected
 * fields the caller already owns. Their clamps mirror the production guard
 * rails ({@code adaptiveMinSettledDecisions} → [4, 80] etc.); the builder
 * applies them on every invocation so a hot-reload of properties stays
 * inside the bounds the production loop has always relied on.
 */
public final class AdaptiveProfileBuilder {

    private AdaptiveProfileBuilder() {
        // utility class — not instantiable
    }

    public static AdaptiveProfile buildAdaptiveProfile(java.util.List<AdaptiveDecisionSample> recentDecisions,
                                                       AdaptiveConfig config,
                                                       LocalDateTime now) {
        int decisions = recentDecisions == null ? 0 : recentDecisions.size();
        int minDecisions = clamp(config.minSettledDecisions(), 4, 80);
        if (decisions < minDecisions) {
            return new AdaptiveProfile(
                    decisions,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    1.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    clamp(config.minEdgeForBet(), 0.0, 0.20),
                    Map.of()
            );
        }

        int triggerMinDecisions = clamp(config.triggerMinDecisions(), 3, 60);
        double weightedWins = 0.0;
        double modelProbSum = 0.0;
        double edgeSum = 0.0;
        double stakeSum = 0.0;
        double pnlSum = 0.0;
        double weightSum = 0.0;
        double squaredWeightSum = 0.0;
        double halfLifeDays = Math.max(2.0, config.learningHalfLifeDays());
        LocalDateTime resolvedNow = now == null ? LocalDateTime.now() : now;
        Map<String, TriggerAggregate> triggerAggregates = new HashMap<>();
        for (AdaptiveDecisionSample sample : recentDecisions) {
            double labelConfidence = clamp(sample.settlementConfidence(), 0.0, 1.0);
            if (labelConfidence < 0.90) {
                continue;
            }
            double w = recencyWeight(sample.eventOccurredAt(), resolvedNow, halfLifeDays) * labelConfidence;
            if (w <= 0.0) {
                continue;
            }
            weightSum += w;
            squaredWeightSum += w * w;
            if (PaperTradeBet.STATUS_WON.equals(sample.status())) {
                weightedWins += w;
            }
            modelProbSum += clamp(sample.modelProbability(), 0.01, 0.99) * w;
            edgeSum += clamp(sample.edge(), -0.25, 0.35) * w;
            stakeSum += Math.max(0.0, sample.stake()) * w;
            pnlSum += sample.profitLoss() * w;

            String triggerKey = normalizeTrigger(sample.topTrigger());
            TriggerAggregate aggregate = triggerAggregates.getOrDefault(triggerKey, TriggerAggregate.empty());
            triggerAggregates.put(triggerKey, aggregate.add(sample, w));
        }

        if (weightSum <= EPS) {
            return AdaptiveProfile.neutral();
        }
        double effectiveSampleSize = squaredWeightSum <= EPS
                ? 0.0
                : (weightSum * weightSum) / squaredWeightSum;

        double observedWinRate = weightedWins / weightSum;
        double avgModelProbability = modelProbSum / weightSum;
        double calibrationError = avgModelProbability - observedWinRate;
        double roiSignal = stakeSum <= EPS ? 0.0 : pnlSum / stakeSum;
        double avgSettledEdge = edgeSum / weightSum;

        // Use effective weighted sample support instead of history window position so
        // adaptation responds to statistical significance, not just queue length.
        double reliabilitySupportTarget = Math.max(4.0, minDecisions * 2.5);
        double reliability = effectiveSampleSize < minDecisions
                ? 0.0
                : clamp(effectiveSampleSize / (effectiveSampleSize + reliabilitySupportTarget), 0.0, 1.0);
        double maxEdgeShift = clamp(config.maxEdgeShift(), 0.002, 0.05);
        double maxScoreShift = clamp(config.maxSelectionScoreShift(), 0.2, 3.0);
        double maxStakeDelta = clamp(config.maxStakeMultiplierDelta(), 0.02, 0.4);

        // Live evidence can tighten risk, but never reward a short winning
        // streak by making production more aggressive. Any loosening must be
        // promoted from an offline, out-of-sample calibration run.
        double edgeShiftRaw = (Math.max(0.0, calibrationError) * 0.08)
                + (Math.max(0.0, -roiSignal) * 0.04);
        double edgeShift = clamp(edgeShiftRaw * reliability, 0.0, maxEdgeShift);
        double modelGapShift = clamp(edgeShift * 0.9, 0.0, maxEdgeShift);
        double selectionScoreShift = clamp(edgeShift * 35.0, 0.0, maxScoreShift);
        double probabilityShiftRaw = -Math.max(0.0, calibrationError) * 0.35;
        double modelProbabilityShift = clamp(probabilityShiftRaw * reliability, -0.035, 0.0);

        double normalizedShift = edgeShift / maxEdgeShift;
        double stakeMultiplier = 1.0 - (normalizedShift * (maxStakeDelta * 0.65));
        stakeMultiplier = clamp(stakeMultiplier, 1.0 - maxStakeDelta, 1.0);

        double confidenceWidthTightening = clamp(Math.max(0.0, edgeShift) * 1.8, 0.0, 0.10);
        double selectionPenalty = clamp(Math.max(0.0, edgeShift) * 24.0, 0.0, 1.2);

        Map<String, TriggerAdaptiveSignal> triggerSignals = new HashMap<>();
        for (Map.Entry<String, TriggerAggregate> entry : triggerAggregates.entrySet()) {
            TriggerAggregate aggregate = entry.getValue();
            double triggerEffectiveN = aggregate.effectiveSampleSize();
            if (aggregate.decisions() < triggerMinDecisions
                    || triggerEffectiveN < triggerMinDecisions
                    || aggregate.weightSum() <= EPS
                    || aggregate.stakeSum() <= EPS) {
                continue;
            }
            double triggerSupportTarget = Math.max(3.0, triggerMinDecisions * 3.0);
            double triggerReliability = clamp(
                    triggerEffectiveN / (triggerEffectiveN + triggerSupportTarget),
                    0.0,
                    1.0
            ) * reliability;
            double triggerWinRate = aggregate.winsWeight() / aggregate.weightSum();
            double triggerModelProb = aggregate.modelProbabilitySum() / aggregate.weightSum();
            double triggerCalibrationError = triggerModelProb - triggerWinRate;
            double triggerRoi = aggregate.pnlSum() / aggregate.stakeSum();
            double triggerProbabilityShift = clamp(
                    -Math.max(0.0, triggerCalibrationError) * 0.45,
                    -0.025,
                    0.0
            ) * triggerReliability;
            double triggerModelGapShift = clamp(
                    (Math.max(0.0, triggerCalibrationError) * 0.28)
                            + (Math.max(0.0, -triggerRoi) * 0.10),
                    0.0,
                    0.015
            ) * triggerReliability;
            double triggerSelectionPenalty = clamp(
                    (Math.max(0.0, triggerCalibrationError) * 8.0)
                            + (Math.max(0.0, -triggerRoi) * 4.0),
                    0.0,
                    1.2
            ) * triggerReliability;
            double triggerEdgeShift = clamp(
                    (Math.max(0.0, triggerCalibrationError) * 0.22)
                            + (Math.max(0.0, -triggerRoi) * 0.10),
                    0.0,
                    0.012
            ) * triggerReliability;

            triggerSignals.put(entry.getKey(), new TriggerAdaptiveSignal(
                    aggregate.decisions(),
                    round4(triggerEffectiveN),
                    round4(triggerProbabilityShift),
                    round4(triggerModelGapShift),
                    round4(triggerSelectionPenalty),
                    round4(triggerEdgeShift)
            ));
        }

        return new AdaptiveProfile(
                decisions,
                round4(effectiveSampleSize),
                round4(reliability),
                round4(edgeShift),
                round4(modelGapShift),
                round4(selectionScoreShift),
                round4(modelProbabilityShift),
                round4(stakeMultiplier),
                round4(confidenceWidthTightening),
                round4(selectionPenalty),
                round4(calibrationError),
                round4(roiSignal),
                round4(avgSettledEdge),
                triggerSignals
        );
    }

    /**
     * Exponential decay by calendar days (not hours) so a Monday settlement
     * and a Tuesday-morning settlement get the same weight. Returns 1.0 when
     * timestamps are missing — keeping legacy behaviour.
     */
    static double recencyWeight(LocalDateTime eventOccurredAt, LocalDateTime now, double halfLifeDays) {
        if (eventOccurredAt == null || now == null) {
            return 1.0;
        }
        long days = Math.max(0L, ChronoUnit.DAYS.between(eventOccurredAt.toLocalDate(), now.toLocalDate()));
        double halfLife = Math.max(2.0, halfLifeDays);
        return Math.pow(0.5, days / halfLife);
    }

    /**
     * Configuration bundle: nine {@code @Value}-injected fields the caller
     * (today {@code PaperTradingService}) hands in at the delegate call site.
     * Keeping them as plain doubles + ints means callers don't need to
     * import any policy types.
     */
    public record AdaptiveConfig(int minSettledDecisions,
                                 int triggerMinDecisions,
                                 double learningHalfLifeDays,
                                 double maxEdgeShift,
                                 double maxSelectionScoreShift,
                                 double maxStakeMultiplierDelta,
                                 double minEdgeForBet) {
    }
}
