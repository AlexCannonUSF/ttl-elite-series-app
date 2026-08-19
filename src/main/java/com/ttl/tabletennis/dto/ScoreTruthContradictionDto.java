package com.ttl.tabletennis.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record ScoreTruthContradictionDto(Long id,
                                         Long evidenceId,
                                         Long betId,
                                         Instant observedAt,
                                         String kind,
                                         double severity,
                                         boolean resolved,
                                         String resolutionNote,
                                         JsonNode payload) {
}
