package com.ttl.tabletennis.scrape;

import java.time.Instant;

public interface RawPayloadStore {

    /**
     * Persist the raw body of an ingested event and return the canonical
     * `rawPayloadRef` to embed back on the event (e.g.
     * {@code s3://ttl-raw/HR_MKT/2026-05-17/<correlationId>}).
     * Implementations are expected to gzip the body before upload.
     * Implementations must be safe to call from the bus publish thread;
     * a failure should surface as a {@link RawPayloadStoreException} so the
     * decorator can decide whether to fail open.
     */
    String put(SourceId source, String correlationId, Instant observedAt, byte[] uncompressedBody);

    default boolean isEnabled() {
        return true;
    }
}
