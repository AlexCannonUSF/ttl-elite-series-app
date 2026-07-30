package com.ttl.tabletennis.settlement;

/**
 * Compact, persisted readout of the score evidence available for one bet.
 *
 * <p>The quality label is deliberately separate from settlement confidence:
 * a live score can be useful to a bettor while still being below the bar for
 * automatic settlement.
 */
public record ScoreEvidenceAssessment(Quality quality,
                                      Finality finality,
                                      double confidence,
                                      int observationCount,
                                      int distinctSourceCount,
                                      int agreeingSourceCount,
                                      int completionSignalCount,
                                      Long inferredWinnerPlayerId,
                                      String latestScore,
                                      String latestPhase,
                                      boolean contradictory) {

    public ScoreEvidenceAssessment {
        quality = quality == null ? Quality.NONE : quality;
        finality = finality == null ? Finality.NONE : finality;
        confidence = Math.max(0.0, Math.min(1.0, Double.isFinite(confidence) ? confidence : 0.0));
        observationCount = Math.max(0, observationCount);
        distinctSourceCount = Math.max(0, distinctSourceCount);
        agreeingSourceCount = Math.max(0, agreeingSourceCount);
        completionSignalCount = Math.max(0, completionSignalCount);
        latestScore = latestScore == null ? "" : latestScore.trim();
        latestPhase = latestPhase == null ? "" : latestPhase.trim();
    }

    public static ScoreEvidenceAssessment none() {
        return new ScoreEvidenceAssessment(
                Quality.NONE,
                Finality.NONE,
                0.0,
                0,
                0,
                0,
                0,
                null,
                "",
                "",
                false
        );
    }

    public boolean decisionGrade() {
        return quality == Quality.DECISION_GRADE && !contradictory && inferredWinnerPlayerId != null;
    }

    public enum Quality {
        NONE,
        WEAK,
        PARTIAL,
        STRONG,
        DECISION_GRADE
    }

    public enum Finality {
        NONE,
        LIVE_PROGRESS,
        EFFECTIVE_FINAL_SCORE,
        MATHEMATICAL_FINAL_SCORE,
        COMPLETION_SIGNAL
    }
}
