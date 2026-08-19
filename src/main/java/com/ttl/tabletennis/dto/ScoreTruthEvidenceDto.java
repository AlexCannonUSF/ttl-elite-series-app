package com.ttl.tabletennis.dto;

import java.time.Instant;
import java.util.List;

public record ScoreTruthEvidenceDto(Instant generatedAt,
                                    String matchId,
                                    ScoreTruthEvidenceSnapshotDto evidence,
                                    List<ScoreTruthContradictionDto> contradictions,
                                    List<ScoreTruthDecisionDto> decisions) {
}
