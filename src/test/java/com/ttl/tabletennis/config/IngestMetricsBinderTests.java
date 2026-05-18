package com.ttl.tabletennis.config;

import com.ttl.tabletennis.repository.IngestDlqRepository;
import com.ttl.tabletennis.scrape.SourceId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IngestMetricsBinderTests {

    @Test
    void registersDlqDepthGaugePerSource() {
        IngestDlqRepository ingestDlqRepository = mock(IngestDlqRepository.class);
        when(ingestDlqRepository.countBySourceId(SourceId.HR_MKT)).thenReturn(4L);
        when(ingestDlqRepository.countBySourceId(SourceId.SOFASCORE)).thenReturn(1L);

        IngestMetricsBinder binder = new IngestMetricsBinder(ingestDlqRepository);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        binder.bindTo(meterRegistry);

        assertEquals(4.0, meterRegistry.get("ingest_dlq_depth").tag("source", "HR_MKT").gauge().value(), 1.0e-9);
        assertEquals(1.0, meterRegistry.get("ingest_dlq_depth").tag("source", "SOFASCORE").gauge().value(), 1.0e-9);
        assertEquals(0.0, meterRegistry.get("ingest_dlq_depth").tag("source", "INTERNAL_DB").gauge().value(), 1.0e-9);
    }
}
