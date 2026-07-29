package com.ttl.tabletennis.dto;

import java.time.Instant;
import java.util.List;

public record OpsIngestDto(Instant generatedAt,
                           OpsIngestBusDto bus,
                           OpsIngestDlqDto dlq,
                           List<OpsIngestPartitionDto> partitions) {
}
