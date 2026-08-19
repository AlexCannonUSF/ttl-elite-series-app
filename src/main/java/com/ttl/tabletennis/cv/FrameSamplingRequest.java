package com.ttl.tabletennis.cv;

import java.time.Instant;

public record FrameSamplingRequest(String matchId,
                                   int sampleRateFps,
                                   long firstSequence,
                                   Instant capturedAtStart) {

    public FrameSamplingRequest {
        matchId = requireText(matchId, "matchId");
        sampleRateFps = Math.max(1, Math.min(2, sampleRateFps));
        firstSequence = Math.max(1L, firstSequence);
        capturedAtStart = capturedAtStart == null ? Instant.now() : capturedAtStart;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
