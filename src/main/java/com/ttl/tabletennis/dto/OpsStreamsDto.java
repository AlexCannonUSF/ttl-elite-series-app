package com.ttl.tabletennis.dto;

import java.time.Instant;
import java.util.List;

public record OpsStreamsDto(Instant generatedAt,
                            OpsStreamsSummaryDto summary,
                            OpsStreamVlmUsageDto vlmUsage,
                            List<OpsStreamWorkerDto> workers) {
}
