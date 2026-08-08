package com.ttl.tabletennis.config;

import com.ttl.tabletennis.repository.IngestDlqRepository;
import com.ttl.tabletennis.scrape.SourceId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

class IngestMetricsBinderTests {

    @Test
    void registersDlqDepthGaugePerSource() {
        IngestDlqRepository ingestDlqRepository = mock(IngestDlqRepository.class);
        doReturn(List.of(
                depth(SourceId.HR_MKT, 4L),
                depth(SourceId.SOFASCORE, 1L)
        )).when(ingestDlqRepository).summarizeDepthBySource();

        IngestMetricsBinder binder = new IngestMetricsBinder(ingestDlqRepository);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        binder.bindTo(meterRegistry);
        binder.refreshDlqDepths();

        assertEquals(4.0, meterRegistry.get("ingest_dlq_depth").tag("source", "HR_MKT").gauge().value(), 1.0e-9);
        assertEquals(1.0, meterRegistry.get("ingest_dlq_depth").tag("source", "SOFASCORE").gauge().value(), 1.0e-9);
        assertEquals(0.0, meterRegistry.get("ingest_dlq_depth").tag("source", "INTERNAL_DB").gauge().value(), 1.0e-9);
    }

    private static IngestDlqRepository.SourceDepth depth(SourceId sourceId, long value) {
        IngestDlqRepository.SourceDepth depth = mock(IngestDlqRepository.SourceDepth.class);
        when(depth.getSourceId()).thenReturn(sourceId);
        when(depth.getDepth()).thenReturn(value);
        return depth;
    }
}
