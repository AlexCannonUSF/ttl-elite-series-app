package com.ttl.tabletennis.settlement.observation;

import com.ttl.tabletennis.scrape.SourceId;
import com.ttl.tabletennis.scrape.TrustTier;

import java.time.Instant;

public record LiveObservation(SourceId source,
                              Instant observedAt,
                              double confidence,
                              MatchPhase phase,
                              ScoreState score,
                              String rawPayloadRef,
                              boolean completionSignal,
                              String bookerEventId,
                              String bookerMarketId,
                              boolean displayed,
                              boolean resulted) implements Observation {

    public LiveObservation {
        source = ObservationSupport.requireSource(source, TrustTier.T1_SPORTSBOOK, "LiveObservation");
        observedAt = ObservationSupport.requireObservedAt(observedAt);
        confidence = ObservationSupport.requireConfidence(confidence);
        phase = ObservationSupport.normalizePhase(phase);
        score = ObservationSupport.normalizeScore(score);
        rawPayloadRef = ObservationSupport.normalizeText(rawPayloadRef);
        bookerEventId = ObservationSupport.requireText(bookerEventId, "bookerEventId");
        bookerMarketId = ObservationSupport.normalizeText(bookerMarketId);
    }
}
