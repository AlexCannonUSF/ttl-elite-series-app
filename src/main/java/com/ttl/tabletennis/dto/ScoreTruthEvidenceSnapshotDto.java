package com.ttl.tabletennis.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record ScoreTruthEvidenceSnapshotDto(Long evidenceId,
                                            Long betId,
                                            String trackedEventId,
                                            Instant bundleAsOf,
                                            String coverageState,
                                            double ambiguityScore,
                                            double confidence,
                                            JsonNode payload) {
}
