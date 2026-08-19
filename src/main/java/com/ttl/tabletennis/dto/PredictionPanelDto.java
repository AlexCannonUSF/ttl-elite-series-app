package com.ttl.tabletennis.dto;

import java.time.Instant;
import java.util.List;

/**
 * Composite payload powering the v3 prediction panel
 * ({@code /v3/matches/:id/prediction}). Phase 05 item 9.
 *
 * <p>Combines the existing v2 prediction snapshot with the new conformal
 * uncertainty envelope and the latest training run's reliability curve so
 * the FE can render probability, calibrated interval, SHAP-style feature
 * contributions, and a reliability diagram in one round-trip.
 */
public record PredictionPanelDto(String matchKey,
                                 long player1Id,
                                 long player2Id,
                                 String modelFamily,
                                 String modelVersion,
                                 String calibrationMethod,
                                 ProbabilityDto pTop,
                                 ProbabilityDto pBot,
                                 ConformalDto conformal,
                                 List<MatchupAnalysisDto.FeatureContributionDto> topContributions,
                                 List<ReliabilityBinDto> reliabilityCurve,
                                 Instant computedAtUtc) {

    public record ProbabilityDto(double value,
                                 double intervalLow,
                                 double intervalHigh) { }

    public record ConformalDto(double coverage,
                               double alpha,
                               String label,
                               double intervalLow,
                               double intervalHigh,
                               double quantile,
                               String method,
                               List<String> predictionSet,
                               String groupKey) { }

    public record ReliabilityBinDto(double lowerBound,
                                    double upperBound,
                                    int count,
                                    double meanPredicted,
                                    double observedRate) { }
}
