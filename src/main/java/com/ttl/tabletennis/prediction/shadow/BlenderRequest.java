package com.ttl.tabletennis.prediction.shadow;

import java.util.Map;

/**
 * Mirrors the {@code /v1/blend} request schema from {@code ttl-predict-py}.
 * Sent verbatim as JSON to the Python service.
 */
public record BlenderRequest(String matchId,
                             String featureSchemaHash,
                             boolean isInPlay,
                             boolean isMajorEvent,
                             Map<String, Object> features) {

    public BlenderRequest {
        if (matchId == null || matchId.isBlank()) {
            throw new IllegalArgumentException("matchId must not be blank");
        }
        if (featureSchemaHash == null || featureSchemaHash.isBlank()) {
            throw new IllegalArgumentException("featureSchemaHash must not be blank");
        }
        if (features == null || features.isEmpty()) {
            throw new IllegalArgumentException("features map must not be empty");
        }
        features = Map.copyOf(features);
    }
}
