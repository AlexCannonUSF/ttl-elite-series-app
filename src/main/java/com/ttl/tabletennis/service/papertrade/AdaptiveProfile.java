package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeSession;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;

import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.round4;

/**
 * Calibration snapshot consumed by the placement loop's eligibility,
 * candidate-resolution, scoring, and adaptive-snapshot paths. Bundles a
 * weighted-reliability score plus shifts for edge, model gap, selection
 * score, model probability, stake multiplier, confidence-width tightening,
 * and selection penalty — driven by recent settled decisions.
 *
 * <p>Lifted from a private nested record in {@code PaperTradingService} as
 * part of the §4 decomposition (slice A: lift the records, slice B will
 * extract the {@code AdaptiveProfileBuilder}). Behaviour is verbatim from
 * the original — the {@link #neutral()} sentinel and the
 * {@link #signalFor(String)} per-trigger lookup keep the same shapes the
 * placement loop relies on.
 */
public record AdaptiveProfile(int sampleSize,
                              double reliability,
                              double edgeShift,
                              double modelGapShift,
                              double selectionScoreShift,
                              double modelProbabilityShift,
                              double stakeMultiplier,
                              double confidenceWidthTightening,
                              double selectionPenalty,
                              double calibrationError,
                              double roiSignal,
                              double avgSettledEdge,
                              Map<String, TriggerAdaptiveSignal> triggerSignals) {

    /** No-adaptation baseline; {@code stakeMultiplier = 1.0} keeps staking unchanged. */
    public static AdaptiveProfile neutral() {
        return new AdaptiveProfile(0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, Map.of());
    }

    /**
     * Writes this profile's adaptive state onto the supplied session entity.
     * No-op on null inputs. The session's {@code adaptiveUpdatedAt} falls
     * back to {@link LocalDateTime#now()} when the caller passes null.
     *
     * <p>Previously this lived as {@code applyAdaptiveSnapshot} inside
     * {@code PaperTradingService}; promoted to the record as an instance
     * method during the §4 decomposition so the placement loop and the
     * reset flow can share it without ferrying a {@code BiConsumer}.
     */
    public void applyTo(PaperTradeSession session, LocalDateTime updatedAt) {
        if (session == null) {
            return;
        }
        session.setAdaptiveSampleSize(sampleSize);
        session.setAdaptiveEdgeShift(round4(edgeShift));
        session.setAdaptiveSelectionScoreShift(round4(selectionScoreShift));
        session.setAdaptiveStakeMultiplier(round4(stakeMultiplier));
        session.setAdaptiveCalibrationError(round4(calibrationError));
        session.setAdaptiveRoiSignal(round4(roiSignal));
        session.setAdaptiveUpdatedAt(updatedAt == null ? LocalDateTime.now() : updatedAt);
    }

    /**
     * Returns the per-trigger nudge for {@code triggerKey} (case-insensitive,
     * trimmed) or a neutral signal when the key is blank, the map is empty,
     * or the trigger has no entry.
     */
    public TriggerAdaptiveSignal signalFor(String triggerKey) {
        if (triggerSignals == null || triggerSignals.isEmpty()) {
            return TriggerAdaptiveSignal.neutral();
        }
        if (!StringUtils.hasText(triggerKey)) {
            return TriggerAdaptiveSignal.neutral();
        }
        return triggerSignals.getOrDefault(triggerKey.trim().toLowerCase(Locale.ROOT), TriggerAdaptiveSignal.neutral());
    }
}
