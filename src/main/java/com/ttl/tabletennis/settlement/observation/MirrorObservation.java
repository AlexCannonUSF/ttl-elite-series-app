package com.ttl.tabletennis.settlement.observation;

import com.ttl.tabletennis.scrape.SourceId;
import com.ttl.tabletennis.scrape.TrustTier;

import java.time.Instant;

public record MirrorObservation(SourceId source,
                                Instant observedAt,
                                double confidence,
                                MatchPhase phase,
                                ScoreState score,
                                String rawPayloadRef,
                                boolean completionSignal,
                                String externalEventId) implements Observation {

    public MirrorObservation {
        source = ObservationSupport.requireSource(source, TrustTier.T2_MIRROR, "MirrorObservation");
        observedAt = ObservationSupport.requireObservedAt(observedAt);
        confidence = ObservationSupport.requireConfidence(confidence);
        phase = ObservationSupport.normalizePhase(phase);
        score = ObservationSupport.normalizeScore(score);
        rawPayloadRef = ObservationSupport.normalizeText(rawPayloadRef);
        externalEventId = ObservationSupport.normalizeText(externalEventId);
    }
}
