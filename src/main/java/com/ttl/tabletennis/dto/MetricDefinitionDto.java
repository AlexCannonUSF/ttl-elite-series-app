package com.ttl.tabletennis.dto;

import java.util.List;

/** One versioned, shared explanation used by both user and admin surfaces. */
public record MetricDefinitionDto(
        String key,
        String category,
        String userLabel,
        String adminLabel,
        String summary,
        String formula,
        String directionality,
        String unit,
        String minimumUsefulSample,
        List<String> caveats,
        List<String> relatedKeys,
        String definitionVersion) {
}
