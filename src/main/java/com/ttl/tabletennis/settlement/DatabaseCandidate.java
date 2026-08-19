package com.ttl.tabletennis.settlement;

import com.ttl.tabletennis.scrape.SourceId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

public record DatabaseCandidate(long matchId,
                                Instant observedAt,
                                LocalDate matchDate,
                                long player1Id,
                                long player2Id,
                                Long winnerPlayerId,
                                String bookerEventId,
                                double confidence,
                                boolean completed,
                                String rawPayloadRef) {

    public DatabaseCandidate {
        if (matchId <= 0L) {
            throw new IllegalArgumentException("matchId must be positive");
        }
        observedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
        matchDate = Objects.requireNonNull(matchDate, "matchDate must not be null");
        if (player1Id <= 0L || player2Id <= 0L) {
            throw new IllegalArgumentException("player ids must be positive");
        }
        if (player1Id == player2Id) {
            throw new IllegalArgumentException("player ids must be distinct");
        }
        if (winnerPlayerId != null && winnerPlayerId != 0L && winnerPlayerId != player1Id && winnerPlayerId != player2Id) {
            throw new IllegalArgumentException("winnerPlayerId must match one of the candidate players");
        }
        bookerEventId = normalizeText(bookerEventId);
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0.0 and 1.0");
        }
        rawPayloadRef = normalizeText(rawPayloadRef);
    }

    public SourceId source() {
        return SourceId.INTERNAL_DB;
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }
}
