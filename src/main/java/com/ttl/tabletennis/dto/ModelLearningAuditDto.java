package com.ttl.tabletennis.dto;

import java.time.Instant;
import java.util.List;

/**
 * Evidence-first operational model report. All performance sections use
 * trusted, resolved outcomes; provisional score guesses are reported
 * separately and never mixed into calibration truth.
 */
public record ModelLearningAuditDto(
        Instant generatedAt,
        int windowDays,
        OutcomeQualityDto outcomeQuality,
        CalibrationEvidenceDto calibrationEvidence,
        List<SegmentPerformanceDto> triggers,
        List<SegmentPerformanceDto> priceRegimes,
        List<FactorPerformanceDto> factors,
        List<ScoreRulePerformanceDto> scoreRules,
        ClvEvidenceDto clv
) {
    public record OutcomeQualityDto(int totalSamples,
                                    int calibrationEligible,
                                    int lowConfidenceExcluded,
                                    int nonBinaryExcluded,
                                    double eligibleCoveragePct) {
    }

    public record CalibrationEvidenceDto(int rawSampleSize,
                                         double effectiveSampleSize,
                                         double meanPredicted,
                                         double observedWinRate,
                                         double calibrationError,
                                         double brierScore,
                                         double logLoss) {
    }

    public record SegmentPerformanceDto(String segment,
                                        int rawSampleSize,
                                        double effectiveSampleSize,
                                        double winRate,
                                        double meanPredicted,
                                        double calibrationError,
                                        double roiPct) {
    }

    public record FactorPerformanceDto(String factor,
                                       int rawSampleSize,
                                       double effectiveSampleSize,
                                       double meanAbsoluteContribution,
                                       double directionalAccuracy,
                                       double meanContributionWhenWon,
                                       double meanContributionWhenLost) {
    }

    public record ScoreRulePerformanceDto(String method,
                                          int resolvedObservations,
                                          int correct,
                                          double accuracy,
                                          double meanStatedConfidence,
                                          double calibrationGap) {
    }

    public record ClvEvidenceDto(int eligibleBets,
                                 int closingLineSamples,
                                 double coveragePct,
                                 Double stakeWeightedClvPct) {
    }
}
