package com.ttl.tabletennis.config;

import com.ttl.tabletennis.repository.IngestDlqRepository;
import com.ttl.tabletennis.scrape.SourceId;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

@Component
public class IngestMetricsBinder implements MeterBinder {

    private final IngestDlqRepository ingestDlqRepository;

    public IngestMetricsBinder(IngestDlqRepository ingestDlqRepository) {
        this.ingestDlqRepository = ingestDlqRepository;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (SourceId sourceId : SourceId.values()) {
            Gauge.builder("ingest_dlq_depth", ingestDlqRepository, repository -> repository.countBySourceId(sourceId))
                    .description("Current ingest dead-letter queue depth by source")
                    .tag("source", sourceId.id())
                    .register(registry);
        }
    }
}
