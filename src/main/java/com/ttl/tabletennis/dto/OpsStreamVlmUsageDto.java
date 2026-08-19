package com.ttl.tabletennis.dto;

import java.time.Instant;

public record OpsStreamVlmUsageDto(boolean enabled,
                                   String meteringState,
                                   int activeForceRequests,
                                   long framesSentToday,
                                   long successfulCallsToday,
                                   long failedCallsToday,
                                   double estimatedCostUsdToday,
                                   Instant lastRequestAt,
                                   String detail) {
}
