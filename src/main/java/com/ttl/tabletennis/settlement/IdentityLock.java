package com.ttl.tabletennis.settlement;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record IdentityLock(long player1Id,
                           long player2Id,
                           Instant placementTime,
                           Duration ambiguityWindow,
                           String bookerEventId,
                           String bookerMarketId) {

    public IdentityLock {
        if (player1Id <= 0L) {
            throw new IllegalArgumentException("player1Id must be positive");
        }
        if (player2Id <= 0L) {
            throw new IllegalArgumentException("player2Id must be positive");
        }
        if (player1Id == player2Id) {
            throw new IllegalArgumentException("player ids must be distinct");
        }
        placementTime = Objects.requireNonNull(placementTime, "placementTime must not be null");
        ambiguityWindow = Objects.requireNonNull(ambiguityWindow, "ambiguityWindow must not be null");
        if (ambiguityWindow.isNegative()) {
            throw new IllegalArgumentException("ambiguityWindow must not be negative");
        }
        bookerEventId = requireText(bookerEventId, "bookerEventId");
        bookerMarketId = normalizeText(bookerMarketId);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }
}
