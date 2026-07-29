package com.ttl.tabletennis.dto;

import java.time.LocalDateTime;
import java.util.List;

public record PaperTradingSessionDto(Long sessionId,
                                     String label,
                                     String status,
                                     double startingBankroll,
                                     double currentBankroll,
                                     double peakBankroll,
                                     double realizedPnl,
                                     double roiPct,
                                     double totalStaked,
                                     double totalReturned,
                                     int totalBets,
                                     int openBets,
                                     int wins,
                                     int losses,
                                     int pushes,
                                     int voidedBets,
                                     long simulationRowsScanned,
                                     long simulationBetsPlaced,
                                     long simulationBetsSettled,
                                     long simulationBetsVoided,
                                     double settledWinRate,
                                     LocalDateTime createdAt,
                                     LocalDateTime updatedAt,
                                     LocalDateTime lastSyncAt,
                                     AdaptiveMetricsDto adaptiveMetrics,
                                     DecisionTelemetryDto decisionTelemetry,
                                     ExposureMetricsDto exposureMetrics,
                                     ClvMetricsDto clvMetrics,
                                     List<PaperTradeBetDto> openBetsList,
                                     List<PaperTradeBetDto> recentBets,
                                     List<TriggerInsightDto> topTriggers,
                                     List<EquityPointDto> equityCurve) {

    public record TriggerInsightDto(String trigger,
                                    int count,
                                    int wins,
                                    int losses,
                                    double winRate,
                                    double pnl,
                                    double avgEdgePct,
                                    double avgModelProbability,
                                    double avgImpliedProbability,
                                    double avgConfidenceWidthPct,
                                    double calibrationDeltaPct,
                                    double roiPct) {
    }

    public record AdaptiveMetricsDto(int sampleSize,
                                     double edgeShiftPct,
                                     double selectionScoreShift,
                                     double stakeMultiplier,
                                     double calibrationErrorPct,
                                     double roiSignalPct,
                                     LocalDateTime updatedAt) {
    }

    public record DecisionTelemetryDto(long consideredCount,
                                       long placedCount,
                                       long skippedCount,
                                       long fallbackPlacedCount,
                                       double placementRatePct,
                                       double avgSelectionScore,
                                       double avgSignalQualityPct,
                                       double avgPlacedEdgePct,
                                       double avgSkippedEdgePct,
                                       List<DecisionReasonDto> topSkipReasons) {
    }

    public record DecisionReasonDto(String reason,
                                    int count) {
    }

    public record ExposureMetricsDto(double openExposure,
                                     double openExposureCap,
                                     double openExposureUsagePct,
                                     double openExposureRemaining,
                                     int maxConcurrentOpenBets,
                                     double concurrentOpenBetUsagePct,
                                     String mostExposedPlayerName,
                                     double mostExposedPlayerStake,
                                     double mostExposedPlayerCap,
                                     double mostExposedPlayerCapUsagePct,
                                     int playerNearCapCount,
                                     String mostExposedTrigger,
                                     double mostExposedTriggerStake,
                                     double mostExposedTriggerCap,
                                     double mostExposedTriggerCapUsagePct,
                                     int triggerNearCapCount) {
    }

    public record ClvMetricsDto(int betsInWindow,
                                int betsWithClosingSnapshot,
                                double coverageRatio,
                                double avgClvPct,
                                double avgPlacedImpliedPct,
                                double avgClosingImpliedPct,
                                LocalDateTime lastClosingSnapshotAt) {
    }

    public record EquityPointDto(LocalDateTime at,
                                 double bankroll,
                                 double cumulativePnl) {
    }
}
