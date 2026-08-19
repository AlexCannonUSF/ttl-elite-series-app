package com.ttl.tabletennis.dto;

import java.util.List;

/** All-call evidence for the active simulation, including non-pick model leans. */
public record LiveRunAnalyticsDto(
        Long sessionId,
        String sessionLabel,
        String generatedAt,
        String evidenceLabel,
        int readinessTarget,
        double readinessPct,
        int totalCalls,
        int settledCalls,
        int awaitingCalls,
        int correct,
        int incorrect,
        double accuracyPct,
        Double accuracyCiLowPct,
        Double accuracyCiHighPct,
        double averageConfidencePct,
        Double brierScore,
        int flatStakeBets,
        int flatStakeWins,
        int flatStakeLosses,
        double flatStakeWagered,
        double flatStakeReturned,
        double flatStakeNetProfit,
        double flatStakeRoiPct,
        Double flatStakeRoiCiLowPct,
        Double flatStakeRoiCiHighPct,
        Double positiveRoiConfidencePct,
        int settledPaperPicks,
        int settledModelOnlyCalls,
        List<TrendPointDto> trend,
        List<SegmentPerformanceDto> triggers,
        List<SegmentPerformanceDto> decisionReasons,
        List<FactorPerformanceDto> factors) {

    public record TrendPointDto(
            int sample,
            String resolvedAt,
            long callId,
            String eventName,
            boolean correct,
            double runningAccuracyPct,
            double cumulativeNetProfit,
            double runningRoiPct) {
    }

    public record SegmentPerformanceDto(
            String segment,
            int sampleSize,
            int wins,
            int losses,
            double accuracyPct,
            Double accuracyCiLowPct,
            Double accuracyCiHighPct,
            double averageModelProbabilityPct,
            double calibrationGapPct,
            double flatStakeNetProfit,
            double flatStakeRoiPct,
            double averageReliabilityPct,
            int readinessTarget,
            double readinessPct) {
    }

    public record FactorPerformanceDto(
            String factor,
            int sampleSize,
            double meanAbsoluteContribution,
            double meanAlignedContribution,
            double directionalAccuracyPct,
            double meanContributionWhenCorrect,
            double meanContributionWhenWrong,
            int readinessTarget,
            double readinessPct) {
    }
}
