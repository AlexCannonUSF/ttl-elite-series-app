package com.ttl.tabletennis.scrape;

import java.time.Instant;

public record FeedHealth(SourceId source,
                         Instant lastSuccess,
                         Instant lastFailure,
                         double rollingSuccessRate5m,
                         double rollingP50LatencyMs,
                         double rollingP95LatencyMs,
                         long stalenessSeconds,
                         int inFlight,
                         String backoffState,
                         String lastError) {

    public FeedHealth {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        if (rollingSuccessRate5m < 0.0 || rollingSuccessRate5m > 1.0) {
            throw new IllegalArgumentException("rollingSuccessRate5m must be between 0.0 and 1.0");
        }
        if (rollingP50LatencyMs < -1.0) {
            throw new IllegalArgumentException("rollingP50LatencyMs must be >= -1.0");
        }
        if (rollingP95LatencyMs < -1.0) {
            throw new IllegalArgumentException("rollingP95LatencyMs must be >= -1.0");
        }
        if (stalenessSeconds < -1L) {
            throw new IllegalArgumentException("stalenessSeconds must be >= -1");
        }
        if (inFlight < 0) {
            throw new IllegalArgumentException("inFlight must be >= 0");
        }
        backoffState = backoffState == null ? "IDLE" : backoffState.trim();
        lastError = lastError == null ? "" : lastError.trim();
    }

    public static FeedHealth idle(SourceId source) {
        return new FeedHealth(source, null, null, 1.0, -1.0, -1.0, -1L, 0, "IDLE", "");
    }
}
