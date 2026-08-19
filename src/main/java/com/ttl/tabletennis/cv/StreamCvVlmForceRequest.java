package com.ttl.tabletennis.cv;

import java.time.Instant;

public record StreamCvVlmForceRequest(String matchId,
                                      String requestedBy,
                                      String reason,
                                      Instant requestedAtUtc,
                                      Instant expiresAtUtc) {

    public StreamCvVlmForceRequest {
        if (matchId == null || matchId.trim().isEmpty()) {
            throw new IllegalArgumentException("matchId must not be blank");
        }
        matchId = matchId.trim();
        requestedBy = requestedBy == null || requestedBy.isBlank() ? "operator" : requestedBy.trim();
        reason = reason == null || reason.isBlank() ? "operator requested VLM fallback" : reason.trim();
        requestedAtUtc = requestedAtUtc == null ? Instant.now() : requestedAtUtc;
        expiresAtUtc = expiresAtUtc == null || !expiresAtUtc.isAfter(requestedAtUtc)
                ? requestedAtUtc.plusSeconds(300)
                : expiresAtUtc;
    }

    public boolean expiredAt(Instant now) {
        Instant effectiveNow = now == null ? Instant.now() : now;
        return !effectiveNow.isBefore(expiresAtUtc);
    }
}
