package com.ttl.tabletennis.scrape;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestMetricsRecorderTests {

    @Test
    void recordPublishedIncrementsPerSourceCounterAndLatencySummary() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        IngestMetricsRecorder recorder = new IngestMetricsRecorder(meterRegistry);

        recorder.recordPublished(new IngestEvent<>(
                SourceId.HR_MKT,
                "odds.updated",
                Instant.now().minusMillis(320),
                0.92,
                "corr-1",
                "raw://1",
                "payload"
        ));
        recorder.recordPublished(new IngestEvent<>(
                SourceId.HR_MKT,
                "score.observed",
                Instant.now().minusMillis(180),
                0.81,
                "corr-2",
                "raw://2",
                "payload"
        ));

        assertEquals(2.0, meterRegistry.get("ingest_events_total").tag("source", "HR_MKT").counter().count(), 1.0e-9);
        assertEquals(2L, meterRegistry.get("ingest_latency_ms").tag("source", "HR_MKT").summary().count());
        assertTrue(meterRegistry.get("ingest_latency_ms").tag("source", "HR_MKT").summary().totalAmount() >= 400.0);
    }
}
