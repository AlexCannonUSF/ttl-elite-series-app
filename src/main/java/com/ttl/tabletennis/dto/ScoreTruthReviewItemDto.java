package com.ttl.tabletennis.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record ScoreTruthReviewItemDto(Long decisionId,
                                      Long betId,
                                      String trackedEventId,
                                      String reason,
                                      Double confidence,
                                      Long evidenceId,
                                      Instant decidedAt,
                                      JsonNode payload,
                                      String reviewStatus,
                                      String reviewer,
                                      String reviewComment,
                                      Instant reviewedAt,
                                      Long reviewActionId) {
}
