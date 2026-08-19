package com.ttl.tabletennis.prediction.shadow;

import java.util.Optional;

/**
 * Subset of the {@code /v1/blend} response we persist into
 * {@code prediction_diff_log}. Fields mirror the Python writer; the
 * {@code sanity} block is populated only when the service has Variant B
 * (with-market) artefacts loaded (Prediction Engine Spec §6.3 + §9.3).
 */
public record BlenderResponse(String matchId,
                              String modelVersion,
                              String calibratorVersion,
                              String conformalVersion,
                              String featureSchemaHash,
                              double pTop,
                              double pBot,
                              double rawPTop,
                              String uncertaintyLabel,
                              double uncertaintyAlpha,
                              double latencyMs,
                              Optional<Sanity> sanity) {

    public BlenderResponse {
        if (pTop < 0.0 || pTop > 1.0) {
            throw new IllegalArgumentException("pTop must lie in [0, 1]");
        }
        if (pBot < 0.0 || pBot > 1.0) {
            throw new IllegalArgumentException("pBot must lie in [0, 1]");
        }
        sanity = sanity == null ? Optional.empty() : sanity;
    }

    public record Sanity(String variant,
                         String modelVersion,
                         String calibratorVersion,
                         String conformalVersion,
                         String featureSchemaHash,
                         double pTop,
                         double pBot,
                         String uncertaintyLabel,
                         double absoluteDiffPTop,
                         double latencyMs) {

        public Sanity {
            if (pTop < 0.0 || pTop > 1.0) {
                throw new IllegalArgumentException("sanity.pTop must lie in [0, 1]");
            }
            if (pBot < 0.0 || pBot > 1.0) {
                throw new IllegalArgumentException("sanity.pBot must lie in [0, 1]");
            }
            if (absoluteDiffPTop < 0.0 || absoluteDiffPTop > 1.0) {
                throw new IllegalArgumentException("sanity.absoluteDiffPTop must lie in [0, 1]");
            }
            variant = variant == null || variant.isBlank() ? "B" : variant.trim();
        }
    }
}
