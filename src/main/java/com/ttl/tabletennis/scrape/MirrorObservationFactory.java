package com.ttl.tabletennis.scrape;

import com.ttl.tabletennis.domain.MirrorObservation;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Component
public class MirrorObservationFactory {

    public MirrorObservation fromPayload(IngestEvent<MirrorObservationPayload> event) {
        MirrorObservationPayload payload = event == null ? null : event.payload();
        if (event == null || payload == null) {
            throw new IllegalArgumentException("event payload must not be null");
        }

        MirrorObservation observation = new MirrorObservation();
        observation.setTrackedEventId(payload.trackedEventId());
        observation.setSourceId(event.source());
        observation.setObservedAt(LocalDateTime.ofInstant(event.observedAt(), ZoneOffset.UTC));
        observation.setPhase(blankToNull(payload.phase()));
        observation.setGamesP1(payload.gamesP1());
        observation.setGamesP2(payload.gamesP2());
        observation.setPointsP1(payload.pointsP1());
        observation.setPointsP2(payload.pointsP2());
        observation.setServer(blankToNull(payload.server()));
        observation.setCompletionSignal(payload.completionSignal());
        observation.setConfidence(event.confidence());
        observation.setCorrelationId(blankToNull(event.correlationId()));
        observation.setPayloadJson(payload.payloadJson());
        observation.setRawPayloadRef(blankToNull(event.rawPayloadRef()));
        return observation;
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
