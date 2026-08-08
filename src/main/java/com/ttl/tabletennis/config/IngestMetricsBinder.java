package com.ttl.tabletennis.config;

import com.ttl.tabletennis.repository.IngestDlqRepository;
import com.ttl.tabletennis.scrape.SourceId;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class IngestMetricsBinder implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(IngestMetricsBinder.class);

    private final IngestDlqRepository ingestDlqRepository;
    private final Map<SourceId, AtomicLong> dlqDepths = new EnumMap<>(SourceId.class);

    public IngestMetricsBinder(IngestDlqRepository ingestDlqRepository) {
        this.ingestDlqRepository = ingestDlqRepository;
        for (SourceId sourceId : SourceId.values()) {
            dlqDepths.put(sourceId, new AtomicLong());
        }
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (SourceId sourceId : SourceId.values()) {
            Gauge.builder("ingest_dlq_depth", dlqDepths.get(sourceId), AtomicLong::doubleValue)
                    .description("Current ingest dead-letter queue depth by source")
                    .tag("source", sourceId.id())
                    .register(registry);
        }
    }

    /**
     * Refresh outside the Prometheus scrape request. A metrics read must never
     * compete with the live board for a Hikari connection.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(
            fixedDelayString = "${ttl.ingestion.metrics.dlqRefreshMs:5000}",
            initialDelayString = "${ttl.ingestion.metrics.dlqInitialDelayMs:5000}")
    public void refreshDlqDepths() {
        try {
            EnumMap<SourceId, Long> latest = new EnumMap<>(SourceId.class);
            for (IngestDlqRepository.SourceDepth row : ingestDlqRepository.summarizeDepthBySource()) {
                if (row != null && row.getSourceId() != null) {
                    latest.put(row.getSourceId(), Math.max(0L, row.getDepth()));
                }
            }
            dlqDepths.forEach((sourceId, gauge) -> gauge.set(latest.getOrDefault(sourceId, 0L)));
        } catch (RuntimeException e) {
            log.debug("Unable to refresh cached ingest DLQ metrics; retaining last values: {}", e.getMessage());
        }
    }
}
