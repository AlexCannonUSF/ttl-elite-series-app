package com.ttl.tabletennis.settlement.observation;

import com.ttl.tabletennis.scrape.SourceId;

import java.time.Instant;

public record DatabaseObservation(Instant observedAt,
                                  double confidence,
                                  MatchPhase phase,
                                  ScoreState score,
                                  String rawPayloadRef,
                                  boolean completionSignal,
                                  long player1Id,
                                  long player2Id,
                                  Long winnerPlayerId) implements Observation {

    public DatabaseObservation {
        observedAt = ObservationSupport.requireObservedAt(observedAt);
        confidence = ObservationSupport.requireConfidence(confidence);
        phase = ObservationSupport.normalizePhase(phase);
        score = ObservationSupport.normalizeScore(score);
        rawPayloadRef = ObservationSupport.normalizeText(rawPayloadRef);
        if (player1Id <= 0L || player2Id <= 0L || player1Id == player2Id) {
            throw new IllegalArgumentException("database observation player ids must be positive and distinct");
        }
        if (winnerPlayerId != null && winnerPlayerId != 0L && winnerPlayerId != player1Id && winnerPlayerId != player2Id) {
            throw new IllegalArgumentException("winnerPlayerId must match one of the observed players");
        }
    }

    @Override
    public SourceId source() {
        return SourceId.INTERNAL_DB;
    }
}
