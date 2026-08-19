package com.ttl.tabletennis.dto;

import java.time.Instant;
import java.util.List;

public record ScoreTruthReviewQueueDto(Instant generatedAt,
                                       int page,
                                       int size,
                                       long totalItems,
                                       int totalPages,
                                       boolean hasPrevious,
                                       boolean hasNext,
                                       List<ScoreTruthReviewItemDto> items) {
}
