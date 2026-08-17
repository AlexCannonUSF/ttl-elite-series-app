package com.ttl.tabletennis.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Paired run comparison with natural and shared-opportunity cohort sizes. */
public record ResearchRunComparisonDto(
        LocalDateTime generatedAt,
        List<Long> requestedRunIds,
        int sharedOpportunityCount,
        List<RunComparison> runs,
        List<String> cautions) {

    public record RunComparison(
            ModelRunHistoryDto.Run run,
            LiveRunAnalyticsDto naturalCohort,
            int distinctOpportunityCount,
            int sharedOpportunityCount,
            double sharedCoveragePct) {
    }
}
