package com.ttl.tabletennis.dto;

import java.time.Instant;
import java.util.List;

public record OpsFeedStatusDto(String sourceId,
                               String trustTier,
                               List<String> capabilities,
                               String lifecycle,
                               String demandState,
                               String cause,
                               String status,
                               boolean liveTick,
                               Double successRate5m,
                               Double p50LatencyMs,
                               Double p95LatencyMs,
                               Long stalenessSeconds,
                               int inFlight,
                               String backoffState,
                               String lastError,
                               long dlqDepth,
                               Instant lastSuccessAt,
                               Instant lastFailureAt,
                               Instant lastSampleAt) {
}
