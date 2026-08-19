package com.ttl.tabletennis.scrape;

import java.time.Instant;

public class NoopRawPayloadStore implements RawPayloadStore {

    @Override
    public String put(SourceId source, String correlationId, Instant observedAt, byte[] uncompressedBody) {
        return "";
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
