package com.ttl.tabletennis.cv;

import java.time.Duration;
import java.util.Optional;

public record VlmScoreReadingResult(Status status,
                                    Optional<VlmScoreReading> reading,
                                    String error,
                                    Duration latency,
                                    int tokensIn,
                                    int tokensOut,
                                    double costEstimateUsd) {

    public enum Status { OK, UNREADABLE, ERROR }

    public VlmScoreReadingResult {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (status == Status.OK && (reading == null || reading.isEmpty())) {
            throw new IllegalArgumentException("OK result must carry a reading");
        }
        reading = reading == null ? Optional.empty() : reading;
        error = error == null ? "" : error;
        latency = latency == null ? Duration.ZERO : latency;
        if (tokensIn < 0) {
            tokensIn = 0;
        }
        if (tokensOut < 0) {
            tokensOut = 0;
        }
        if (costEstimateUsd < 0.0) {
            costEstimateUsd = 0.0;
        }
    }

    public static VlmScoreReadingResult ok(VlmScoreReading reading,
                                           Duration latency,
                                           int tokensIn,
                                           int tokensOut,
                                           double costEstimateUsd) {
        return new VlmScoreReadingResult(Status.OK, Optional.of(reading), "", latency, tokensIn, tokensOut, costEstimateUsd);
    }

    public static VlmScoreReadingResult unreadable(String reason, Duration latency, int tokensIn, int tokensOut, double costEstimateUsd) {
        return new VlmScoreReadingResult(Status.UNREADABLE, Optional.empty(), reason, latency, tokensIn, tokensOut, costEstimateUsd);
    }

    public static VlmScoreReadingResult error(String reason, Duration latency) {
        return new VlmScoreReadingResult(Status.ERROR, Optional.empty(), reason, latency, 0, 0, 0.0);
    }
}
