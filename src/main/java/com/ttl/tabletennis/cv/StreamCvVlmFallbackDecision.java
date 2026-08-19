package com.ttl.tabletennis.cv;

import java.time.Instant;

public record StreamCvVlmFallbackDecision(String matchId,
                                          String frameId,
                                          StreamCvVlmFallbackTrigger trigger,
                                          String reason,
                                          String requestedBy,
                                          Instant requestedAtUtc,
                                          Instant decidedAtUtc,
                                          Instant expiresAtUtc) {

    public StreamCvVlmFallbackDecision {
        if (matchId == null || matchId.trim().isEmpty()) {
            throw new IllegalArgumentException("matchId must not be blank");
        }
        matchId = matchId.trim();
        frameId = frameId == null ? "" : frameId.trim();
        trigger = trigger == null ? StreamCvVlmFallbackTrigger.OPERATOR_FORCE : trigger;
        reason = reason == null || reason.isBlank() ? trigger.name() : reason.trim();
        requestedBy = requestedBy == null || requestedBy.isBlank() ? "system" : requestedBy.trim();
        requestedAtUtc = requestedAtUtc == null ? Instant.now() : requestedAtUtc;
        decidedAtUtc = decidedAtUtc == null ? Instant.now() : decidedAtUtc;
        expiresAtUtc = expiresAtUtc == null ? decidedAtUtc : expiresAtUtc;
    }
}
