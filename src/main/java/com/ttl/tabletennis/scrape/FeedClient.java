package com.ttl.tabletennis.scrape;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Flow;

public interface FeedClient<T> {

    SourceId source();

    List<IngestEvent<T>> pullOnce(PullContext ctx);

    default Optional<Flow.Publisher<IngestEvent<T>>> stream(StreamContext ctx) {
        return Optional.empty();
    }

    FeedHealth currentHealth();

    default BackoffPolicy backoff() {
        return BackoffPolicy.phaseZeroDefault();
    }

    default Set<Capability> capabilities() {
        return Set.of();
    }

    record PullContext(Instant requestedAt,
                       String correlationId,
                       Map<String, String> attributes) {

        public PullContext {
            requestedAt = requestedAt == null ? Instant.now() : requestedAt;
            correlationId = correlationId == null ? "" : correlationId.trim();
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }

        public static PullContext now(String correlationId) {
            return new PullContext(Instant.now(), correlationId, Map.of());
        }
    }

    record StreamContext(Instant requestedAt,
                         String correlationId,
                         Map<String, String> attributes) {

        public StreamContext {
            requestedAt = requestedAt == null ? Instant.now() : requestedAt;
            correlationId = correlationId == null ? "" : correlationId.trim();
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }

        public static StreamContext now(String correlationId) {
            return new StreamContext(Instant.now(), correlationId, Map.of());
        }
    }

    record BackoffPolicy(Duration initialDelay,
                         Duration maxDelay,
                         double multiplier,
                         double jitterFactor) {

        public BackoffPolicy {
            initialDelay = initialDelay == null ? Duration.ofSeconds(1) : initialDelay;
            maxDelay = maxDelay == null ? Duration.ofSeconds(30) : maxDelay;
            if (initialDelay.isNegative() || initialDelay.isZero()) {
                throw new IllegalArgumentException("initialDelay must be positive");
            }
            if (maxDelay.isNegative() || maxDelay.isZero()) {
                throw new IllegalArgumentException("maxDelay must be positive");
            }
            if (maxDelay.compareTo(initialDelay) < 0) {
                throw new IllegalArgumentException("maxDelay must be >= initialDelay");
            }
            if (multiplier < 1.0) {
                throw new IllegalArgumentException("multiplier must be >= 1.0");
            }
            if (jitterFactor < 0.0 || jitterFactor > 1.0) {
                throw new IllegalArgumentException("jitterFactor must be between 0.0 and 1.0");
            }
        }

        public static BackoffPolicy phaseZeroDefault() {
            return new BackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(30), 2.0, 0.20);
        }
    }

    enum Capability {
        ODDS,
        SCORES,
        RESULTS,
        RANKINGS,
        MARKET_STATE,
        STREAM_URL,
        BOOKER_EVENT_ID,
        POINT_BY_POINT,
        COMPLETION_SIGNAL
    }
}
