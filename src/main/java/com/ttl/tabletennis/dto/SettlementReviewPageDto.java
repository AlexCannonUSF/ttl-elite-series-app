package com.ttl.tabletennis.dto;

import java.time.Instant;
import java.util.List;

public record SettlementReviewPageDto(Instant generatedAt,
                                      int page,
                                      int size,
                                      long totalItems,
                                      int totalPages,
                                      boolean hasPrevious,
                                      boolean hasNext,
                                      long suspiciousItems,
                                      long highTrustItems,
                                      long lowTrustItems,
                                      List<SettlementReviewItemDto> items) {
}
