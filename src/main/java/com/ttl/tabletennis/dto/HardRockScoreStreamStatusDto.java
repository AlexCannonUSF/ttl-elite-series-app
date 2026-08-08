package com.ttl.tabletennis.dto;

import java.time.Instant;

/**
 * Operator-visible state for the durable Hard Rock score transport.
 *
 * <p>The market API and the score stream intentionally have separate health:
 * a wager can close while the score stream continues through the final point.
 */
public record HardRockScoreStreamStatusDto(
        boolean enabled,
        boolean connected,
        int trackedEvents,
        int liveEvents,
        int completedEventsCached,
        Instant connectedAt,
        Instant lastMessageAt,
        Instant lastScoreAt,
        long reconnectCount,
        String lastError
) {
}
