package com.ttl.tabletennis.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ResearchRunCompareRequest(
        @NotEmpty(message = "Choose at least one run")
        @Size(max = 12, message = "Compare at most 12 runs at once")
        List<Long> runIds,
        Integer trendLimit) {
}
