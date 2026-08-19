package com.ttl.tabletennis.dto;

import java.time.Instant;
import java.util.List;

public record OpsFeedsDto(Instant generatedAt,
                          OpsFeedsSummaryDto summary,
                          List<OpsFeedStatusDto> feeds) {
}
