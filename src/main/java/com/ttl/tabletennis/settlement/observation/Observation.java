package com.ttl.tabletennis.settlement.observation;

import com.ttl.tabletennis.scrape.SourceId;
import com.ttl.tabletennis.scrape.TrustTier;

import java.time.Instant;

public sealed interface Observation permits LiveObservation, MirrorObservation, StreamObservation, OfficialObservation, DatabaseObservation {

    SourceId source();

    default TrustTier tier() {
        return source().tier();
    }

    Instant observedAt();

    double confidence();

    MatchPhase phase();

    ScoreState score();

    String rawPayloadRef();

    boolean completionSignal();
}
