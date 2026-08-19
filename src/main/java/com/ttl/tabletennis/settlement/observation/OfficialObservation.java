package com.ttl.tabletennis.settlement.observation;

import com.ttl.tabletennis.scrape.SourceId;
import com.ttl.tabletennis.scrape.TrustTier;

import java.time.Instant;
import java.util.Objects;

public record OfficialObservation(SourceId source,
                                  Instant observedAt,
                                  double confidence,
                                  MatchPhase phase,
                                  ScoreState score,
                                  String rawPayloadRef,
                                  boolean completionSignal,
                                  long player1Id,
                                  long player2Id,
                                  Long winnerPlayerId) implements Observation {

    public OfficialObservation {
        source = Objects.requireNonNull(source, "source must not be null");
        if (source.tier() != TrustTier.T4_CONFIRMATION || source == SourceId.INTERNAL_DB) {
            throw new IllegalArgumentException("OfficialObservation requires a non-database T4 confirmation source");
        }
        observedAt = ObservationSupport.requireObservedAt(observedAt);
        confidence = ObservationSupport.requireConfidence(confidence);
        phase = ObservationSupport.normalizePhase(phase);
        score = ObservationSupport.normalizeScore(score);
        rawPayloadRef = ObservationSupport.normalizeText(rawPayloadRef);
        if (player1Id <= 0L || player2Id <= 0L || player1Id == player2Id) {
            throw new IllegalArgumentException("official observation player ids must be positive and distinct");
        }
        if (winnerPlayerId != null && winnerPlayerId != 0L && winnerPlayerId != player1Id && winnerPlayerId != player2Id) {
            throw new IllegalArgumentException("winnerPlayerId must match one of the observed players");
        }
    }
}
