package com.ttl.tabletennis.dto;

public record OpsStreamsSummaryDto(int totalWorkers,
                                   int enabledWorkers,
                                   int offWorkers,
                                   int activeWorkers,
                                   int availableComponents,
                                   String pipelineStatus,
                                   int routeOverrides,
                                   int routeWarnings,
                                   int roiTemplates,
                                   int activeForceRequests) {
}
