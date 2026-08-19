package com.ttl.tabletennis.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ReplayDto(
        Long id,
        Long parentReplayId,
        String label,
        String status,
        String replayMode,
        List<Long> sourceRunIds,
        LocalDateTime windowStart,
        LocalDateTime windowEnd,
        String captureRule,
        List<String> modelLaneKeys,
        List<String> portfolioKeys,
        String executionBook,
        double initialBankroll,
        int maxQuoteAgeSeconds,
        long deterministicSeed,
        String definitionChecksum,
        String leakageAuditStatus,
        boolean reproducible,
        int eventCount,
        int resolvedCount,
        int pricedResolvedCount,
        int correctCount,
        double accuracyPct,
        double flatStakePnl,
        double flatStakeRoiPct,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        List<Event> events,
        List<String> integrityNotes) {

    public record Event(
            int sequenceNumber,
            Long sourceRunId,
            Long sourceCallId,
            LocalDateTime eventTime,
            String eventType,
            String eventName,
            String captureType,
            String predictedWinnerName,
            Double modelProbability,
            Integer hardRockAmericanOdds,
            String decisionStatus,
            String pipelineStage,
            String effectiveOutcome,
            String outcomeSource,
            Double flatStakeProfit) {
    }
}
