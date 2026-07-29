package com.ttl.tabletennis.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Composite payload powering {@code /v3/ml/quality} (Phase 05 item 10).
 *
 * <p>Pairs the training-time calibration bins (championship model) with a
 * trailing-{@code windowDays} recalculation over settled paper-trade
 * decisions plus simple drift signals so operators can see whether the
 * production model has drifted away from its training distribution.
 */
public record MlQualityDto(int windowDays,
                           Instant computedAtUtc,
                           ReliabilitySnapshot training,
                           ReliabilitySnapshot recent,
                           List<HistogramBin> probabilityHistogram,
                           List<DailyCount> dailyVolume,
                           DriftSummary drift) {

    public record ReliabilitySnapshot(String label,
                                      int sampleCount,
                                      Double ece,
                                      Double maxBinDeviation,
                                      Double brierScore,
                                      List<ReliabilityBin> bins) { }

    public record ReliabilityBin(double lowerBound,
                                 double upperBound,
                                 int count,
                                 double meanPredicted,
                                 double observedRate) { }

    public record HistogramBin(double lowerBound,
                               double upperBound,
                               int count) { }

    public record DailyCount(LocalDate date,
                             int predictions) { }

    public record DriftSummary(Double eceDelta,
                               Double meanPredictedDelta,
                               Double meanObservedDelta,
                               String severity) { }
}
