package com.ttl.tabletennis.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.time.LocalDateTime;
import java.util.List;

public record ReplayDefinitionRequest(
        @NotBlank String label,
        @NotEmpty List<Long> sourceRunIds,
        String replayMode,
        LocalDateTime windowStart,
        LocalDateTime windowEnd,
        String captureRule,
        List<String> modelLaneKeys,
        List<String> portfolioKeys,
        String executionBook,
        Double initialBankroll,
        Integer maxQuoteAgeSeconds,
        Long deterministicSeed) {
}
