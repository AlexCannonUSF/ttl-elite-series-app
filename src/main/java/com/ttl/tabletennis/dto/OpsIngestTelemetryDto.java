package com.ttl.tabletennis.dto;

import java.time.Instant;

public record OpsIngestTelemetryDto(long published,
                                    long decoded,
                                    long validated,
                                    long dispatched,
                                    long acknowledged,
                                    long rejected,
                                    long dlq,
                                    long pollFailures,
                                    long parityDelta,
                                    long redeliveries,
                                    Double throughputPerMinute,
                                    Instant consumerHeartbeatAt,
                                    Instant lastProcessedAt,
                                    Long latestEventAgeMs,
                                    boolean fullTrafficCoverage,
                                    Long soakSeconds,
                                    String soakStatus) {
}
