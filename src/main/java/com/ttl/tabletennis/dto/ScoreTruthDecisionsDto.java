package com.ttl.tabletennis.dto;

import java.time.Instant;
import java.util.List;

public record ScoreTruthDecisionsDto(Instant generatedAt,
                                     Instant from,
                                     List<ScoreTruthDecisionDto> decisions) {
}
