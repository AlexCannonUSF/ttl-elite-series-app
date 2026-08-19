package com.ttl.tabletennis.settlement;

import com.ttl.tabletennis.scrape.SourceId;
import com.ttl.tabletennis.scrape.TrustTier;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

public record OfficialCandidate(SourceId source,
                                long matchId,
                                Instant observedAt,
                                LocalDate matchDate,
                                long player1Id,
                                long player2Id,
                                Long winnerPlayerId,
                                String bookerEventId,
                                double confidence,
                                boolean completed,
                                String rawPayloadRef) {

    public OfficialCandidate {
        source = Objects.requireNonNull(source, "source must not be null");
        if (source.tier() != TrustTier.T4_CONFIRMATION || source == SourceId.INTERNAL_DB) {
            throw new IllegalArgumentException("official candidates must use non-database T4 confirmation sources");
        }
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

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }
}
