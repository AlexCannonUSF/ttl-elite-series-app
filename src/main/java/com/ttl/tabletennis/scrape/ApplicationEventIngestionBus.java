package com.ttl.tabletennis.scrape;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class ApplicationEventIngestionBus implements IngestionBus {

    private final ApplicationEventPublisher applicationEventPublisher;
    private final IngestMetricsRecorder ingestMetricsRecorder;

    public ApplicationEventIngestionBus(ApplicationEventPublisher applicationEventPublisher,
                                        IngestMetricsRecorder ingestMetricsRecorder) {
        this.applicationEventPublisher = applicationEventPublisher;
        this.ingestMetricsRecorder = ingestMetricsRecorder;
    }

    @Override
    public void publish(IngestEvent<?> event) {
        if (event == null) {
            return;
        }
        ingestMetricsRecorder.recordPublished(event);
        applicationEventPublisher.publishEvent(event);
    }
}
