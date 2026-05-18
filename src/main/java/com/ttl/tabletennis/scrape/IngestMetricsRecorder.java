package com.ttl.tabletennis.scrape;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

@Component
public class IngestMetricsRecorder {

    private final MeterRegistry meterRegistry;
    private final Map<SourceId, Counter> eventCounters = new EnumMap<>(SourceId.class);
    private final Map<SourceId, DistributionSummary> latencySummaries = new EnumMap<>(SourceId.class);

    public IngestMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordPublished(IngestEvent<?> event) {
        if (event == null || event.source() == null) {
            return;
        }

        counter(event.source()).increment();
        latencySummary(event.source()).record(latencyMillis(event.observedAt()));
    }

    private Counter counter(SourceId sourceId) {
        return eventCounters.computeIfAbsent(sourceId, source -> Counter.builder("ingest_events_total")
                .description("Total ingest events published onto the ingestion bus")
                .tag("source", source.id())
                .register(meterRegistry));
    }

    private DistributionSummary latencySummary(SourceId sourceId) {
        return latencySummaries.computeIfAbsent(sourceId, source -> DistributionSummary.builder("ingest_latency_ms")
                .description("Observed ingest latency in milliseconds from source observation to bus publish")
                .baseUnit("milliseconds")
                .publishPercentiles(0.50, 0.95)
                .serviceLevelObjectives(250.0, 500.0, 1000.0, 2500.0, 5000.0)
                .tag("source", source.id())
                .register(meterRegistry));
    }

    private double latencyMillis(Instant observedAt) {
        if (observedAt == null) {
            return 0.0;
        }
        return Math.max(0.0, Duration.between(observedAt, Instant.now()).toMillis());
    }
}
