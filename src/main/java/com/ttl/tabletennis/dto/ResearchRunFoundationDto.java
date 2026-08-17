package com.ttl.tabletennis.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Shared-opportunity research graph summary for one run. */
public record ResearchRunFoundationDto(
        long runId,
        long opportunityCount,
        long legacyModelCallCount,
        long synchronizedOpportunityCount,
        double telemetryCompletenessPct,
        List<ModelLane> modelLanes,
        List<Portfolio> portfolios,
        List<Benchmark> benchmarks,
        List<Annotation> annotations) {

    public record ModelLane(Long id, String laneKey, String displayName, String role, int ordinal,
                            String modelFamily, String modelVersion, String artifactChecksum,
                            String featureSchemaChecksum, String calibrationId, boolean enabled,
                            boolean primary, long evaluations, double opportunityCoveragePct,
                            long resolved, long correct, double accuracyPct, Double brierScore,
                            long pricedResolved, double flatStakePnl, double flatStakeRoiPct) { }

    public record Portfolio(Long id, String portfolioKey, String displayName, String type,
                            String modelLaneKey, String policyVersion, boolean enabled, boolean primary,
                            long decisions, long actioned, long passed, double opportunityCoveragePct,
                            long resolved, long correct, double accuracyPct, long pricedResolved,
                            double flatStakePnl, double flatStakeRoiPct) { }

    public record Benchmark(String benchmarkKey, long evaluations, double opportunityCoveragePct,
                            long resolved, long correct, double accuracyPct, long pricedResolved,
                            double flatStakePnl, double flatStakeRoiPct) { }

    public record Annotation(Long id, String targetType, String targetId, String text,
                             List<String> tags, String author, LocalDateTime createdAt) { }
}
