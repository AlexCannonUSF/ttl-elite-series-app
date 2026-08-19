package com.ttl.tabletennis.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record ScoreTruthDecisionDto(Long id,
                                    Long betId,
                                    String trackedEventId,
                                    String decision,
                                    String reason,
                                    Double confidence,
                                    Long evidenceId,
                                    Instant decidedAt,
                                    JsonNode payload) {
}
