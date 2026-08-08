package com.ttl.tabletennis.dto;

import java.time.Instant;
import java.util.List;

public record OpsIngestDto(Instant generatedAt,
                           OpsIngestBusDto bus,
                           OpsIngestTelemetryDto telemetry,
                           OpsIngestDlqDto dlq,
                           List<OpsIngestPartitionDto> partitions) {
    public OpsIngestDto(Instant generatedAt,
                        OpsIngestBusDto bus,
                        OpsIngestDlqDto dlq,
                        List<OpsIngestPartitionDto> partitions) {
        this(generatedAt, bus, null, dlq, partitions);
    }
}
