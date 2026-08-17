package com.ttl.tabletennis.dto;

import java.time.LocalDateTime;

/**
 * One research-grade run payload. All sections are scoped to the same
 * explicit session id; no component is allowed to fall back to the active
 * session.
 */
public record ResearchRunDetailDto(
        LocalDateTime generatedAt,
        ModelRunHistoryDto.Run run,
        ModelCallScorecardDto scorecard,
        LiveRunAnalyticsDto analytics,
        ModelCallMonitorDto pipeline,
        ResearchRunFoundationDto foundation,
        Integrity integrity) {

    public record Integrity(
            boolean modelIdentityComplete,
            boolean datasetWindowKnown,
            boolean closedRunImmutable,
            int postCloseCallCount,
            boolean settlementCoverageComplete,
            int totalCalls,
            int settledCalls,
            int awaitingCalls,
            double settlementCoveragePct,
            String status,
            String explanation) {
    }
}
