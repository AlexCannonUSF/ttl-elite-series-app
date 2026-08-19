package com.ttl.tabletennis.dto;

import java.time.Instant;
import java.util.List;

public record OpsSettlementDiffsDto(Instant generatedAt,
                                    String focus,
                                    int page,
                                    int size,
                                    long filteredRows,
                                    int totalPages,
                                    boolean hasPrevious,
                                    boolean hasNext,
                                    OpsSettlementDiffSummaryDto summary,
                                    List<OpsSettlementDiffRowDto> rows) {
}
