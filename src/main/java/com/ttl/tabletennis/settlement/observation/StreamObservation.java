package com.ttl.tabletennis.settlement.observation;

import com.ttl.tabletennis.scrape.SourceId;
import com.ttl.tabletennis.scrape.TrustTier;

import java.time.Instant;

public record StreamObservation(SourceId source,
                                Instant observedAt,
                                double confidence,
                                MatchPhase phase,
                                ScoreState score,
                                String rawPayloadRef,
                                boolean completionSignal,
                                String routeId,
                                String templateId,
                                int consensusFrames) implements Observation {

    public StreamObservation {
        source = ObservationSupport.requireSource(source, TrustTier.T3_STREAM_CV, "StreamObservation");
        observedAt = ObservationSupport.requireObservedAt(observedAt);
        confidence = ObservationSupport.requireConfidence(confidence);
        phase = ObservationSupport.normalizePhase(phase);
        score = ObservationSupport.normalizeScore(score);
        rawPayloadRef = ObservationSupport.normalizeText(rawPayloadRef);
        routeId = ObservationSupport.normalizeText(routeId);
        templateId = ObservationSupport.normalizeText(templateId);
        if (consensusFrames < 0) {
            throw new IllegalArgumentException("consensusFrames must not be negative");
        }
    }
}
