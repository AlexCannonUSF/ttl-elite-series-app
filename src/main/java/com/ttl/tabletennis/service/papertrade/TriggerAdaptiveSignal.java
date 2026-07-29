package com.ttl.tabletennis.service.papertrade;

/**
 * Per-trigger nudge applied by the adaptive loop. Bundled four levers — a
 * probability shift, a model-gap shift, a selection penalty, and an
 * edge-threshold shift — each driven by the calibration error and ROI
 * signal of recent settled decisions tagged with that trigger.
 *
 * <p>Lifted from a private nested record in {@code PaperTradingService} as
 * part of the §4 decomposition (slice A: lift the records, slice B will
 * extract the builder). Behaviour is verbatim from the original.
 *
 * @param sampleSize          number of settled decisions that fed this signal
 * @param probabilityShift    additive nudge to the model probability for this trigger
 * @param modelGapShift       additive nudge to the model-vs-implied gap threshold
 * @param selectionPenalty    additive penalty applied to the selection score
 * @param edgeThresholdShift  additive nudge to the minimum-edge gate
 */
public record TriggerAdaptiveSignal(int sampleSize,
                                    double probabilityShift,
                                    double modelGapShift,
                                    double selectionPenalty,
                                    double edgeThresholdShift) {

    public static TriggerAdaptiveSignal neutral() {
        return new TriggerAdaptiveSignal(0, 0.0, 0.0, 0.0, 0.0);
    }
}
