package com.ttl.tabletennis.scrape;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ApplicationEventIngestionBusTests {

    @Test
    void publishDelegatesToSpringApplicationEventPublisher() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        IngestMetricsRecorder ingestMetricsRecorder = mock(IngestMetricsRecorder.class);
        ApplicationEventIngestionBus ingestionBus = new ApplicationEventIngestionBus(publisher, ingestMetricsRecorder);

        IngestEvent<String> event = new IngestEvent<>(
                SourceId.HR_MKT,
                "odds.updated",
                Instant.parse("2026-04-16T12:00:00Z"),
                0.95,
                "corr-1",
                "raw://1",
                "payload"
        );

        ingestionBus.publish(event);

        verify(ingestMetricsRecorder).recordPublished(event);
        verify(publisher).publishEvent(event);
    }
}
