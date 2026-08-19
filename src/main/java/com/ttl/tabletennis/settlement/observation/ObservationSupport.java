package com.ttl.tabletennis.settlement.observation;

import com.ttl.tabletennis.scrape.SourceId;
import com.ttl.tabletennis.scrape.TrustTier;

import java.time.Instant;
import java.util.Objects;

final class ObservationSupport {

    private ObservationSupport() {
    }

    static SourceId requireSource(SourceId source, TrustTier expectedTier, String observationType) {
        source = Objects.requireNonNull(source, "source must not be null");
        if (source.tier() != expectedTier) {
            throw new IllegalArgumentException(observationType + " requires source tier " + expectedTier + " but got " + source.tier());
        }
        return source;
    }

    static Instant requireObservedAt(Instant observedAt) {
        return Objects.requireNonNull(observedAt, "observedAt must not be null");
    }

    static double requireConfidence(double confidence) {
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0.0 and 1.0");
        }
        return confidence;
    }

    static MatchPhase normalizePhase(MatchPhase phase) {
        return phase == null ? MatchPhase.UNKNOWN : phase;
    }

    static ScoreState normalizeScore(ScoreState score) {
        return score == null ? ScoreState.unknown() : score;
    }

    static String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
